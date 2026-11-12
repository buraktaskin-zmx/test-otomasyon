package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The bank's frame around the screen, as tape 2628c4fd shows it (12.11.2026). */
class PageChromeTest {

    /** The page Firma Fatura İşlemleri opened on, trimmed to its shape. */
    private static final String OPENED = """
            ### Snapshot
            ```yaml
            - application [active] [ref=f3e1]:
              - generic [ref=f3e2]:
                - generic [ref=f3e172]:
                  - list:
                    - listitem [ref=f3e173]:
                      - link "Çıkış" [ref=f3e174] [cursor=pointer]:
                        - /url: "#"
              - generic [ref=f3e9]:
                - generic [ref=f3e187]:
                  - list [ref=f3e188]:
                    - listitem [ref=f3e191]:
                      - link "HESAPLAR" [ref=f3e192] [cursor=pointer]
                    - listitem [ref=f3e203]:
                      - link "NAKİT YÖNETİMİ" [ref=f3e204] [cursor=pointer]
                - generic [ref=f3e406]:
                  - generic [ref=f3e407]:
                    - generic [ref=f3e408]:
                      - heading "Firma Fatura İşlemleri" [level=2] [ref=f3e410]
                  - generic [ref=f3e413]:
                    - generic [ref=f3e419]: Firma Seçimi
                    - link "Fatura İptal Et" [ref=f3e747] [cursor=pointer]
                  - generic [ref=f3e479]:
                    - link "Pdf" [ref=f3e480] [cursor=pointer]
              - generic [ref=f3e56]:
                - generic [ref=f3e60]: Copyright © 2026 Yapı ve Kredi Bankası A.Ş.
              - status
            ```""";

    private static final String SCENARIO = """
            Senaryo adı: İptal ekranından Geri ile listeye dönülür
            1. "Fatura İptal Et" düğmesine tıkla""";

    @Test
    @DisplayName("header, main menu and footer go; the screen, and what opens later, stay")
    void frameGoesScreenStays() {
        PageChrome chrome = PageChrome.learn(OPENED, "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri", SCENARIO);
        assertThat(chrome.size()).isEqualTo(3);

        String later = OPENED.replace("  - status",
                "  - dialog \"Uyarı\" [ref=f4e10]:\n    - button \"Evet\" [ref=f4e11]\n  - status");
        String shown = chrome.strip(later);

        assertThat(shown)
                .doesNotContain("Çıkış").doesNotContain("HESAPLAR").doesNotContain("NAKİT YÖNETİMİ")
                .doesNotContain("Copyright")
                .contains("heading \"Firma Fatura İşlemleri\"")
                .contains("generic [ref=f3e419]: Firma Seçimi")
                .contains("link \"Fatura İptal Et\" [ref=f3e747]")
                .contains("link \"Pdf\" [ref=f3e480]")
                // appeared after the screen opened: never part of the frame
                .contains("dialog \"Uyarı\" [ref=f4e10]").contains("button \"Evet\" [ref=f4e11]")
                .contains(PageChrome.NOTE);
        assertThat(shown.split(java.util.regex.Pattern.quote(PageChrome.NOTE), -1)).hasSize(2);
    }

    @Test
    @DisplayName("a frame part the scenario names is kept")
    void namedFrameIsKept() {
        PageChrome chrome = PageChrome.learn(OPENED, "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri",
                SCENARIO + "\n2. \"Çıkış\" bağlantısına tıkla");

        String shown = chrome.strip(OPENED);

        assertThat(shown).contains("Çıkış").doesNotContain("HESAPLAR").doesNotContain("Copyright");
    }

    @Test
    @DisplayName("no heading, no menu, or a page with new references: nothing is left out")
    void unknownShapeLeavesEverything() {
        assertThat(PageChrome.learn(OPENED, "NAKİT YÖNETİMİ", "Başka Ekran", SCENARIO)).isSameAs(PageChrome.NONE);
        assertThat(PageChrome.learn(OPENED, "ÖDEMELER", "Firma Fatura İşlemleri", SCENARIO))
                .isSameAs(PageChrome.NONE);

        PageChrome chrome = PageChrome.learn(OPENED, "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri", SCENARIO);
        String reloaded = OPENED.replace("ref=f3e", "ref=f9e");
        assertThat(chrome.strip(reloaded)).isEqualTo(reloaded);
    }

    @Test
    @DisplayName("only when code opened the screen")
    void onlyOnScreen() {
        var loggedIn = new AiRunPrelude.Outcome(AiRunPrelude.Reached.LOGGED_IN, OPENED, null);
        assertThat(PageChrome.forRun(loggedIn, "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri", SCENARIO))
                .isSameAs(PageChrome.NONE);

        var onScreen = new AiRunPrelude.Outcome(AiRunPrelude.Reached.ON_SCREEN, OPENED, null);
        PageChrome chrome = PageChrome.forRun(onScreen, "NAKİT YÖNETİMİ", "Firma Fatura İşlemleri", SCENARIO);
        String first = chrome.shown(onScreen, SCENARIO, 12_000).page();
        assertThat(first).doesNotContain("HESAPLAR").doesNotContain("/url").contains("Fatura İptal Et");
    }
}
