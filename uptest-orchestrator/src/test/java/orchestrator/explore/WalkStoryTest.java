package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The step-by-step story of a walk, told from the tape: logged in, which
 * screen, what was clicked, what the screen then showed.
 */
class WalkStoryTest {

    @TempDir
    Path dir;

    private static String page(String yaml) {
        return "### Page\n- Page URL: x\n### Snapshot\n```yaml\n" + yaml + "\n```";
    }

    private static final String LIST = page("""
            - generic [ref=e1]:
              - heading "Firma Fatura İşlemleri" [level=2] [ref=e2]
              - generic [ref=e3]:
                - generic [ref=e4]: Firma Seçimi
                - combobox [ref=e10] [cursor=pointer]:
                  - textbox "Seçiniz" [ref=e12]""");

    private static final String OPEN = page("""
            - generic [ref=e1]:
              - heading "Firma Fatura İşlemleri" [level=2] [ref=e2]
              - generic [ref=e3]:
                - generic [ref=e4]: Firma Seçimi
                - combobox [expanded] [ref=e10]:
                  - listbox [ref=e20]:
                    - option "ASAŞ ALÜMİNYUM SAN." [ref=e23]""");

    private static final String GRID = page("""
            - generic [ref=e1]:
              - heading "Firma Fatura İşlemleri" [level=2] [ref=e2]
              - table [ref=e39]:
                - rowgroup [ref=e38]:
                  - row [ref=e37]:
                    - columnheader "Fatura No" [ref=e36]
                  - row [ref=e40]:
                    - cell "HLLSRC03" [ref=e43]
                    - cell "Ödenecek" [ref=e44]
                  - row [ref=e50]:
                    - cell "HLLSRC12" [ref=e53]
                    - cell "Ödendi" [ref=e54]
              - generic [ref=e59]:
                - heading "İŞLEMLER" [level=1] [ref=e58]
                - link "Fatura İptal Et" [ref=e61]""");

    private static final String CANCEL = page("""
            - generic [ref=e1]:
              - heading "Fatura İptal" [level=2] [ref=e80]
              - button "Evet" [ref=e81]
              - button "Hayır" [ref=e82]""");

    private static final String CANCEL_WARNED = page("""
            - generic [ref=e1]:
              - heading "Fatura İptal" [level=2] [ref=e80]
              - alertdialog "Uyarı" [ref=e90]:
                - generic [ref=e91]: Fatura zaten iptal edilmiş.
              - button "Hayır" [ref=e82]""");

    private List<WalkStory.Line> story() {
        ActionTape tape = ActionTape.open(dir, "explore", "", "", "", List.of());
        tape.record(AiRunPrelude.LOGIN_TOOL, "{\"app\":\"ngc\",\"cif\":\"QZ06395288\",\"userId\":\"3014\"}",
                ActionTape.Outcome.OK, "Giriş yapıldı");
        tape.record(AiRunPrelude.MENU_TOOL, "{\"mainMenu\":\"NAKİT YÖNETİMİ\",\"screen\":\"Firma Fatura İşlemleri\"}",
                ActionTape.Outcome.OK, "açıldı");
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, LIST);
        tape.record("browser_click", "{\"target\":\"e10\",\"element\":\"Firma Seçimi\"}", ActionTape.Outcome.OK, "ok");
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, OPEN);
        tape.record("browser_click", "{\"target\":\"e23\"}", ActionTape.Outcome.OK, "ok");
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, GRID);
        tape.record("browser_click", "{\"target\":\"e44\"}", ActionTape.Outcome.OK, "ok");
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, GRID);
        tape.record("browser_click", "{\"target\":\"e61\"}", ActionTape.Outcome.OK, "ok");
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, CANCEL);
        tape.record("browser_click", "{\"target\":\"e81\",\"element\":\"Evet\"}", ActionTape.Outcome.REFUSED,
                "REFUSED: \"evet\" commits something the scenario never asked for");
        tape.record("browser_click", "{\"target\":\"e82\"}", ActionTape.Outcome.OK, "ok");
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, CANCEL_WARNED);
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, GRID);
        tape.record(RunVerdict.TOOL, "{}", ActionTape.Outcome.OK, "Sonuç kaydedildi");
        return WalkStory.of(tape.calls());
    }

    private static String render(List<WalkStory.Line> lines) {
        StringBuilder out = new StringBuilder();
        for (WalkStory.Line line : lines) {
            out.append("  ".repeat(line.depth())).append(line.kind()).append(' ').append(line.text()).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("login, the screen, each click under its screen, what each screen showed")
    void tellsTheWalk() {
        assertThat(render(story())).isEqualTo("""
                ok Giriş yapıldı (CIF QZ06395288)
                ok NAKİT YÖNETİMİ › Firma Fatura İşlemleri açıldı
                  screen Firma Fatura İşlemleri
                    ok "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seçildi
                      seen görüldü: ızgarada 2 satır
                    ok "HLLSRC03" satırı seçildi
                    ok "Fatura İptal Et" bağlantısına tıklandı
                  screen Fatura İptal (yeni ekran)
                    refused "Evet" düğmesine — reddedildi: "evet" commits something the scenario never asked for
                    ok "Hayır" düğmesine tıklandı
                      seen görüldü: uyarı "Uyarı": Fatura zaten iptal edilmiş.
                  screen Firma Fatura İşlemleri (geri dönüldü)
                    seen görüldü: ızgarada 2 satır
                """);
    }

    @Test
    @DisplayName("a looping walk is cut, and says how much was left out")
    void longWalkIsCapped() {
        ActionTape tape = ActionTape.open(dir, "explore", "", "", "", List.of());
        tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, CANCEL);
        for (int i = 0; i < 100; i++) {
            tape.record("browser_click", "{\"target\":\"e82\"}", ActionTape.Outcome.OK, "ok");
        }
        List<WalkStory.Line> lines = WalkStory.of(tape.calls());

        assertThat(lines).hasSize(WalkStory.MAX_LINES);
        assertThat(lines.get(lines.size() - 1).text()).contains("satır daha");
    }
}
