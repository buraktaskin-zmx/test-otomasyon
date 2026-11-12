package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool results were 26% of a measured walk, nearly all of it one page per
 * turn. The page may shrink; what the agent can act on may not.
 */
class LeanResultsTest {

    private static final String RESULT = """
            ### Ran Playwright code
            ```js
            await page.getByRole('link', { name: 'Detay' }).click();
            ```
            ### Page
            - Page URL: https://ifctest.yapikredi.com.tr/ngc/x.do
            ### Page Snapshot
            ```yaml
            - generic [ref=e1]:
              - banner [ref=e2]:
                - generic [ref=e3]:
                  - link "Firma Fatura İşlemleri" [ref=e4]
              - main [ref=e5]:
                - list [ref=e6]:
                  - listitem [ref=e7]:
                    - button "Fatura İptal Et" [ref=e8]
                - rowgroup [ref=e9]:
                  - row "955231 222,00 TL Ödenecek" [ref=e10]:
                    - cell [ref=e11]:
                      - radio [ref=e12]
                    - cell "955231" [ref=e13]
                - generic [ref=e14]
                - textbox "Fatura No" [ref=e15]
                - region "Ödenecekler" [ref=e16]
            ```""";

    @Test
    @DisplayName("everything the agent can act on keeps its line and its reference")
    void actionableNodesSurvive() {
        String lean = LeanResults.summarise(RESULT, 10_000);

        assertThat(lean)
                .contains("link \"Firma Fatura İşlemleri\" [ref=e4]")
                .contains("button \"Fatura İptal Et\" [ref=e8]")
                .contains("row \"955231 222,00 TL Ödenecek\" [ref=e10]")
                .contains("cell \"955231\" [ref=e13]")
                .contains("textbox \"Fatura No\" [ref=e15]")
                // An unnamed radio is a control: it is how a row gets picked.
                .contains("radio [ref=e12]")
                // A NAMED region says where you are, so it stays.
                .contains("region \"Ödenecekler\" [ref=e16]")
                // The address is how the agent knows which screen it is on.
                .contains("Page URL");
    }

    @Test
    @DisplayName("the scaffolding and the echo of the agent's own call go")
    void scaffoldAndEchoGo() {
        String lean = LeanResults.summarise(RESULT, 10_000);

        assertThat(lean)
                .doesNotContain("Ran Playwright code")
                .doesNotContain("getByRole")
                .doesNotContain("generic [ref=e1]")
                .doesNotContain("generic [ref=e14]")
                .doesNotContain("list [ref=e6]")
                .doesNotContain("listitem [ref=e7]")
                .doesNotContain("rowgroup [ref=e9]");
        assertThat(lean.length()).isLessThan(RESULT.length() * 2 / 3);
    }

    @Test
    @DisplayName("a result bigger than the cap is cut, and says so")
    void capStillApplies() {
        String huge = RESULT + "\n- button \"x\" [ref=e99]".repeat(500);
        String lean = LeanResults.summarise(huge, 500);
        assertThat(lean).hasSizeLessThan(800).contains("kesildi");
    }

    @Test
    @DisplayName("12.11.2026: MCP's real shape — a JSON array, escaped line breaks — is summarised too")
    void realMcpShapeIsUnwrapped() {
        String wrapped = "[{\"text\":\"### Page\\n- Page URL: x\\n### Snapshot\\n```yaml\\n"
                + "- generic [ref=f3e417]:\\n"
                + "  - generic [ref=f3e418]:\\n"
                + "    - generic [ref=f3e419]: Firma Seçimi\\n"
                + "    - combobox [ref=f3e422] [cursor=pointer]:\\n"
                + "      - textbox \\\"Seçiniz\\\" [ref=f3e423]\\n"
                + "  - list [ref=f3e733]:\\n"
                + "    - listitem [ref=f3e746]:\\n"
                + "      - link \\\"Fatura İptal Et\\\" [ref=f3e747] [cursor=pointer]:\\n"
                + "        - /url: \\\"#\\\"\\n"
                + "```\"}]";

        String lean = LeanResults.summarise(wrapped, 10_000);

        assertThat(lean)
                .doesNotContain("\\n")
                .doesNotContain("generic [ref=f3e417]")
                .doesNotContain("listitem [ref=f3e746]")
                // A label is text of its own, not scaffolding.
                .contains("generic [ref=f3e419]: Firma Seçimi")
                .contains("textbox \"Seçiniz\" [ref=f3e423]")
                .contains("link \"Fatura İptal Et\" [ref=f3e747]");
    }

    @Test
    @DisplayName("12.11.2026: /url, cursor, console counters and the snapshot-file pointer go; refs stay")
    void noiseGoesRefsStay() {
        String page = """
                ### Page
                - Page URL: https://x/entryNgc.nhnc.do
                - Page Title: Kurumsal İnternet Şubesi | Yapı Kredi
                - Console: 12 errors, 16 warnings
                ### Snapshot
                - [Snapshot](runs/mcp/page-2026-11-12T11-11-25-186Z.yml)
                ### Events
                - New console entries: runs/mcp/console.log#L73-L74
                ### Snapshot
                ```yaml
                - link "Çıkış" [ref=f1e174] [cursor=pointer]:
                  - /url: "#"
                - link "Sayın Buse Tam Yetkili TEST AMAÇLI unvan" [ref=f1e183] [cursor=pointer]:
                  - /url: "#"
                  - text: Sayın Buse Tam Yetkili
                  - generic [ref=f1e184]: TEST AMAÇLI unvan
                - row [ref=f1e649]:
                  - 'columnheader "Bayi Kodu: activate to sort column ascending" [ref=f1e651] [cursor=pointer]':
                    - generic [ref=f1e652]: Bayi Kodu
                - generic [ref=f1e419]: Firma Seçimi
                - combobox [ref=f1e604] [cursor=pointer]:
                  - textbox "ASAŞ ALÜMİNYUM SAN." [ref=f1e605]
                ```""";

        String lean = LeanResults.summarise(page, 100_000, "");

        assertThat(lean)
                .doesNotContain("/url").doesNotContain("cursor=pointer").doesNotContain("Console:")
                .doesNotContain("Page Title").doesNotContain("[Snapshot](").doesNotContain("Events")
                .doesNotContain("activate to sort")
                // a child repeating its parent's name
                .doesNotContain("text: Sayın Buse Tam Yetkili").doesNotContain("f1e652")
                .contains("Page URL")
                .contains("link \"Çıkış\" [ref=f1e174]\n")
                .contains("columnheader \"Bayi Kodu\" [ref=f1e651]")
                // a label is not a repeat: its parent is not named after it
                .contains("generic [ref=f1e419]: Firma Seçimi")
                .contains("textbox \"ASAŞ ALÜMİNYUM SAN.\" [ref=f1e605]");
        assertThat(lean.split("### Snapshot", -1)).hasSize(2);
    }

    @Test
    @DisplayName("12.11.2026: 113 firms -> the chosen one, the ones the scenario names, the first few, and the way to the rest")
    void longOptionListKeepsWhatTheScenarioNames() {
        StringBuilder list = new StringBuilder("- listbox [expanded] [ref=f1e485]:\n");
        list.append("  - option \"Seçiniz\" [selected] [ref=f1e486]\n");
        for (int i = 0; i < 100; i++) {
            list.append("  - option \"Firma ").append(i).append("\" [ref=f1e").append(500 + i).append("]\n");
        }
        list.append("  - option \"ASAŞ ALÜMİNYUM SAN.\" [ref=f1e493]\n");
        list.append("  - option \"AYDEM\" [ref=f1e494]\n");
        String scenario = "1. firma seçimi alanından asaş alüminyum san. seç";

        String lean = LeanResults.summarise(list.toString(), 100_000, scenario);

        assertThat(lean)
                .contains("option \"Seçiniz\" [selected] [ref=f1e486]")
                .contains("option \"ASAŞ ALÜMİNYUM SAN.\" [ref=f1e493]")
                .contains("option \"Firma 0\"").contains("option \"Firma 1\"")
                .doesNotContain("option \"Firma 50\"").doesNotContain("AYDEM")
                // 103 options: Seçiniz, Firma 0, Firma 1 (the head) and ASAŞ are kept
                .contains("99 seçenek daha gösterilmedi")
                .contains("browser_press_sequentially");
    }

    @Test
    @DisplayName("a short list is shown whole, scenario or not")
    void shortListIsWhole() {
        StringBuilder list = new StringBuilder("- listbox [ref=e1]:\n");
        for (int i = 0; i < LeanResults.OPTIONS_SHOWN; i++) {
            list.append("  - option \"O").append(i).append("\" [ref=e").append(10 + i).append("]\n");
        }
        String lean = LeanResults.summarise(list.toString(), 100_000, "");
        assertThat(lean).contains("O0").contains("O" + (LeanResults.OPTIONS_SHOWN - 1))
                .doesNotContain("gösterilmedi");
    }

    @Test
    void nullAndPlainTextArePassedThrough() {
        assertThat(LeanResults.summarise(null, 100)).isNull();
        assertThat(LeanResults.summarise("Clicked", 100)).isEqualTo("Clicked");
    }
}
