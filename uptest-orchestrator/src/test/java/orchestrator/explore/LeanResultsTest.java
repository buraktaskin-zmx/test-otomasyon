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
    void nullAndPlainTextArePassedThrough() {
        assertThat(LeanResults.summarise(null, 100)).isNull();
        assertThat(LeanResults.summarise("Clicked", 100)).isEqualTo("Clicked");
    }
}
