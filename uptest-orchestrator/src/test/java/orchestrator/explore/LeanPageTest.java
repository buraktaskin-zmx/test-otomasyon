package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool results were half of the walk that got cut off at 100.000 tokens, and
 * nearly all of that was the page. The page may shrink; what the agent can act
 * on, and what the application says when it refuses, may not.
 */
class LeanPageTest {

    /**
     * The shape these screens really produce: a header and a menu, a form
     * whose boxes have no accessible name and whose labels are sibling
     * generics, and a result grid of rows wrapping cells wrapping generics.
     */
    private static final String RESULT = """
            ### Ran Playwright code
            ```js
            await page.getByRole('link', { name: 'Detay' }).click();
            ```
            ### Page state
            - Page URL: https://ifctest.yapikredi.com.tr/ngc/x.do
            - Page Title: Firma Fatura İşlemleri
            - Page Snapshot
            ```yaml
            - generic [ref=e1]:
              - banner [ref=e2]:
                - generic [ref=e3]:
                  - link "Çıkış" [ref=e4]
                - navigation [ref=e5]:
                  - list [ref=e6]:
                    - listitem [ref=e7]:
                      - link "NAKİT YÖNETİMİ" [ref=e8]
                    - listitem [ref=e9]:
                      - link "ÖDEMELER" [ref=e10]
              - main [ref=e11]:
                - region "Ödenecekler" [ref=e12]
                - generic [ref=e13]: Firma Kodu / Müşteri Kodu
                - textbox [ref=e14]
                - generic [ref=e15]: Fatura No
                - textbox [ref=e16]: 955231
                - button "Fatura İptal Et" [ref=e17]
                - text "Toplam tutar giriniz"
                - table [ref=e18]:
                  - rowgroup [ref=e19]:
                    - row "955231 222,00 TL Ödenecek" [ref=e20]:
                      - cell [ref=e21]:
                        - radio [ref=e22]
                      - cell "955231" [ref=e23]:
                        - generic [ref=e24]: 955231
                      - cell "222,00 TL" [ref=e25]
                      - cell "Ödenecek" [ref=e26]
                      - cell [ref=e27]:
                        - link "Detay" [ref=e28]
                    - row "955232 118,00 TL Ödendi" [ref=e29]:
                      - cell [ref=e30]:
                        - radio [ref=e31]
                      - cell "955232" [ref=e32]
                      - cell "118,00 TL" [ref=e33]
                      - cell "Ödendi" [ref=e34]
            ```
            ### Modal state
            - dialog "Onaylıyor musunuz?" is open""";

    @Test
    @DisplayName("everything the agent can act on keeps its line and its reference")
    void actionableNodesSurvive() {
        String lean = LeanResults.summarise(RESULT, 10_000, true);

        assertThat(lean)
                .contains("e17 button \"Fatura İptal Et\"")
                // An unnamed box wears the label printed next to it, in
                // brackets: it is the page's word, not the element's own name.
                .contains("e14 textbox [Firma Kodu / Müşteri Kodu]")
                .contains("e16 textbox [Fatura No] = \"955231\"")
                // A named container says where you are.
                .contains("e12 region \"Ödenecekler\"");
    }

    @Test
    @DisplayName("a grid row is one line, and the controls inside it keep their references")
    void gridRowCollapsesButKeepsItsControls() {
        String lean = LeanResults.summarise(RESULT, 10_000, true);

        assertThat(lean)
                .contains("satır e20 955231 | 222,00 TL | Ödenecek (radio e22, link e28 \"Detay\")")
                .contains("satır e29 955232 | 118,00 TL | Ödendi (radio e31)")
                // The cells themselves are gone: the row spoke for them.
                .doesNotContain("cell \"955231\"")
                .doesNotContain("e23")
                .doesNotContain("rowgroup");
    }

    @Test
    @DisplayName("a row whose cells say less than its own name keeps the name")
    void richRowNameWins() {
        // The shape the old filter's fixture had: the row knows it is a
        // 222,00 TL invoice that is due, and only one of its cells carries
        // text. Preferring the cells there would throw the price and the
        // status away, which is most of what the scenario is about.
        String thin = """
                ### Page Snapshot
                ```yaml
                - rowgroup [ref=e9]:
                  - row "955231 222,00 TL Ödenecek" [ref=e10]:
                    - cell [ref=e11]:
                      - radio [ref=e12]
                    - cell "955231" [ref=e13]
                ```""";

        assertThat(LeanPage.of(thin, true))
                .contains("satır e10 955231 222,00 TL Ödenecek (radio e12)");
    }

    @Test
    @DisplayName("what the application says when it refuses is never dropped")
    void validationTextSurvives() {
        // It has no reference — Playwright hands those out for things you can
        // act on — so the ordinary AriaSnapshot parse drops it entirely.
        assertThat(LeanResults.summarise(RESULT, 10_000, true))
                .contains("text \"Toplam tutar giriniz\"");
    }

    @Test
    @DisplayName("everything outside the snapshot block passes through untouched")
    void surroundingSectionsSurvive() {
        String lean = LeanResults.summarise(RESULT, 10_000, true);

        assertThat(lean)
                .contains("Page URL: https://ifctest.yapikredi.com.tr/ngc/x.do")
                .contains("Page Title: Firma Fatura İşlemleri")
                // The open dialog is the one thing a shrunken page must still
                // mention: browser_handle_dialog depends on it.
                .contains("### Modal state")
                .contains("dialog \"Onaylıyor musunuz?\" is open")
                // The agent's own call echoed back at it still goes.
                .doesNotContain("Ran Playwright code")
                .doesNotContain("getByRole");
    }

    @Test
    @DisplayName("the header and the menu go when code opened the screen, and stay when it did not")
    void chromeGoesOnlyWhenTheAgentCannotNavigate() {
        assertThat(LeanResults.summarise(RESULT, 10_000, true))
                .doesNotContain("NAKİT YÖNETİMİ")
                .doesNotContain("ÖDEMELER")
                .doesNotContain("Çıkış");

        assertThat(LeanResults.summarise(RESULT, 10_000, false))
                .contains("e8 link \"NAKİT YÖNETİMİ\"")
                .contains("e10 link \"ÖDEMELER\"");
    }

    @Test
    @DisplayName("no reference that can be acted on is lost — the safety argument, as a check")
    void everyActionableReferenceSurvives() {
        String lean = LeanPage.of(RESULT, true);
        Set<String> kept = LeanPage.refsIn(lean);

        // Every control, button, link and row of the form half of the page.
        assertThat(kept).contains("e12", "e14", "e16", "e17", "e20", "e22", "e28", "e29", "e31");
        // The menu's references are the only ones allowed to go, and only
        // because the agent has no tool that could use them.
        assertThat(kept).doesNotContain("e8", "e10");
    }

    @Test
    @DisplayName("it is dramatically smaller, which is the whole point")
    void itIsSmaller() {
        String lean = LeanResults.summarise(RESULT, 10_000, true);
        assertThat(lean.length()).isLessThan(RESULT.length() / 2);
    }

    @Test
    @DisplayName("a result with no tree in it is left to the line filter, byte for byte")
    void nonSnapshotResultsAreUntouched() {
        assertThat(LeanPage.of("Clicked", true)).isNull();
        assertThat(LeanPage.of(null, true)).isNull();
        assertThat(LeanResults.summarise("Clicked", 100, true)).isEqualTo("Clicked");
        // A fence with nothing parseable in it must not swallow the result.
        String noTree = "### Page Snapshot\n```yaml\nnothing here\n```";
        assertThat(LeanPage.of(noTree, true)).isNull();
        assertThat(LeanResults.summarise(noTree, 1000, true)).contains("nothing here");
    }

    @Test
    @DisplayName("a page bigger than the cap is still cut, and still says so")
    void capStillApplies() {
        String lean = LeanResults.summarise(RESULT, 400, true);
        assertThat(lean).hasSizeLessThan(700).contains("kesildi");
    }
}
