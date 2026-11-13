package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Beklenen answered, in code. The fixtures are tape bd40c3ee
 * (12.11.2026): "Güncelleme ekranındaki yeni değer alanları düzenlenebilir",
 * reported green with neither final Beklenen answered.
 */
class ExpectationCheckTest {

    /** The instruction as that run sent it (the older UI numbered the step Beklenen). */
    private static final String SCENARIO = """
            Senaryo adı (yalnızca bir isim, yapılacak bir adım DEĞİL): Güncelleme ekranındaki yeni değer alanları düzenlenebilir

            Adımlar — numara sırasıyla, birer birer uygula:
            1. "Firma Seçimi" listesinden "ASAŞ ALÜMİNYUM SAN." seç
            2. Izgarada Ödeme Durumu "Ödenecek" olan ilk satırı seç ve başka satır seçme
            3. İşlemler panelindeki "Fatura Güncelle" bağlantısına tıkla
            4. Beklenen: "<güncelleme ekranının başlığı>" ekranı açılır
            5. Açılan güncelleme ekranında yalnızca "Geri" düğmesine tıkla

            Beklenen:
            - Kurum Adı, Bayi Kodu, Bayi Ünvanı, Fatura No, Para Birimi, Fatura Tutarı, Son Ödeme Tarihi ve Fatura Dönemi alanları salt okunur görünür.
            - Ayrıca sayfanın asagısında "yeni" ile başlayan alanlar değerleri ile gözükür ve buradaki değerlerin hiçbiri düzenlenebilir değildir salt okunurdur""";

    /** The update screen, lines as the tape holds them (seq 11). */
    private static final String UPDATE_PAGE = """
            - heading "Fatura Güncelle" [level=2] [ref=e767]
            - generic [ref=e785]: Kurum Adı
            - generic [ref=e786]: ASAŞ ALÜMİNYUM SAN.
            - generic [ref=e788]: Bayi Kodu
            - generic [ref=e791]: Bayi Ünvanı
            - generic [ref=e794]: Fatura No
            - generic [ref=e800]: Para Birimi
            - generic [ref=e803]: Fatura Tutarı
            - generic [ref=e809]: Son Ödeme Tarihi
            - textbox "Yeni Fatura No" [ref=e822]: HLLSRC03
            - textbox "Yeni Fatura Tutarı" [ref=e836]: "25"
            - textbox "Yeni Fatura Dönemi" [ref=e852]
            - link "Geri" [ref=e923] [cursor=pointer]""";

    /** The list after "Geri" (seq 13): the grid's headers repeat some names, not all. */
    private static final String LIST_PAGE = """
            - heading "Firma Fatura İşlemleri" [level=2] [ref=e932]
            - generic [ref=e941]: Firma Seçimi
            - generic [ref=e1004]: Bayi Kodu
            - generic [ref=e1006]: Fatura No
            - generic [ref=e1008]: Son Ödeme Tarihi
            - generic [ref=e1010]: Fatura Tutarı
            - link "Fatura Güncelle" [ref=e1094] [cursor=pointer]""";

    private static List<TapeRenderer.Call> walk() {
        return List.of(
                new TapeRenderer.Call(9, "browser_snapshot", "{}", "OK", "- heading \"Firma Fatura İşlemleri\""),
                new TapeRenderer.Call(10, "browser_click", "{\"target\":\"e743\"}", "OK", ""),
                new TapeRenderer.Call(11, "browser_snapshot", "{}", "OK", UPDATE_PAGE),
                new TapeRenderer.Call(12, "browser_click", "{\"target\":\"e923\"}", "OK", ""),
                new TapeRenderer.Call(13, "browser_snapshot", "{}", "OK", LIST_PAGE));
    }

    private static RunVerdict verdict() {
        RunVerdict verdict = new RunVerdict();
        verdict.expecting(StepExpectations.all(SCENARIO));
        return verdict;
    }

    @Test
    @DisplayName("the step Beklenen and each final item become B1, B2, B3")
    void everyExpectationIsListed() {
        List<StepExpectations.Expectation> all = StepExpectations.all(SCENARIO);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).afterStep()).isEqualTo(3);
        assertThat(all.get(1).afterStep()).isZero();
        assertThat(all.get(1).text()).startsWith("Kurum Adı");
        assertThat(all.get(2).text()).startsWith("Ayrıca");
        assertThat(StepExpectations.checklist(all))
                .contains("B1 (3. adımdan HEMEN SONRAKİ ekranda)")
                .contains("B3 (SON ekranda): Ayrıca");
    }

    @Test
    @DisplayName("bd40c3ee as it was reported: no expectation answered is Geçmedi, not Geçti")
    void unansweredExpectationsFail() {
        RunVerdict verdict = verdict();
        verdict.acceptJson("""
                {"succeeded": ["Firma seçildi", "Satır seçildi", "Fatura Güncelle bağlantısına tıklandı",
                  "Fatura Güncelle ekranı açıldı", "Geri düğmesine tıklandı ve ana ekrana dönüldü"],
                 "failed": [], "warnings": [], "passed": true}""");
        assertThat(verdict.result().passed()).isFalse();
        assertThat(verdict.result().failed()).hasSize(3)
                .allMatch(line -> line.contains("kontrol edilmedi"));
    }

    @Test
    @DisplayName("d7b61934: the whole answer written inside 'succeeded' is read, not lost as 0/5")
    void answerInsideTheFirstString() {
        RunVerdict verdict = new RunVerdict();
        verdict.expecting(StepExpectations.all("1. \"Devam\" düğmesine tıkla\n"
                + "   Beklenen (bu adımdan hemen sonra kontrol et): onay ekranı açılır\n\nBeklenen:\n"
                + "- Onay ekranında \"Fatura Tipi\" alanı \"Normal\" değerini gösterir"));
        // The verdict turn's report_result, exactly as the tape held its arguments.
        verdict.acceptJson("{\"succeeded\": \"[\\\"1. adım: Devam düğmesine tıklandı ve onay ekranı açıldı.\\\"],"
                + " \\\"failed\\\": [], \\\"warnings\\\": [\\\"10. adımda girilen 1000,50 tutarı onay ekranında"
                + " 100.050,00 TL olarak gösterilmiştir.\\\"], \\\"expectations\\\": [\\\"B1 GÖRÜLDÜ: Onay ekranı"
                + " açılmış.\\\", \\\"B2 GÖRÜLDÜ: Fatura Tipi alanı 'Normal' değerini gösterir.\\\"],"
                + " \\\"passed\\\": true}\"}");

        RunVerdict.Result result = verdict.result();
        assertThat(result.succeeded()).contains("1. adım: Devam düğmesine tıklandı ve onay ekranı açıldı.")
                .anyMatch(line -> line.startsWith("Beklenen (son ekranda) görüldü"));
        assertThat(result.warnings()).singleElement().asString().contains("100.050,00");
        assertThat(result.failed()).isEmpty();
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("answers are matched by B-number, in any order; only GÖRÜLDÜ counts as seen")
    void answersByNumber() {
        RunVerdict verdict = verdict();
        verdict.reportResult(List.of("Adımlar yapıldı"), List.of(), List.of(),
                List.of("B3 GÖRÜLMEDİ: Yeni alanlar düzenlenebilir kutular.",
                        "B1 GÖRÜLDÜ: Fatura Güncelle başlığı açıldı.",
                        "B2 görüldü — alanlar düz metin"), true);
        RunVerdict.Result result = verdict.result();
        assertThat(result.passed()).isFalse();
        assertThat(result.succeeded()).anyMatch(line -> line.startsWith("Beklenen (3. adımdan sonra) görüldü")
                && line.endsWith("Fatura Güncelle başlığı açıldı."));
        assertThat(result.succeeded()).anyMatch(line -> line.startsWith("Beklenen (son ekranda) görüldü: Kurum Adı")
                && line.endsWith("alanlar düz metin"));
        assertThat(result.failed()).singleElement().asString()
                .startsWith("Beklenen (son ekranda) görülmedi: Ayrıca")
                .endsWith("Yeni alanlar düzenlenebilir kutular.");
    }

    @Test
    @DisplayName("unnumbered answers are taken in order; an answer with no status is not 'seen'")
    void unnumberedInOrder() {
        RunVerdict verdict = verdict();
        verdict.reportResult(List.of(), List.of(), List.of(),
                List.of("GÖRÜLDÜ: başlık doğru", "GÖRÜLDÜ: salt okunur", "alanlar galiba salt okunur"), true);
        assertThat(verdict.result().succeeded()).hasSize(2);
        assertThat(verdict.result().failed()).singleElement().asString().contains("görülmedi");
    }

    @Test
    @DisplayName("a scenario without Beklenen, and a result the code reports, are judged as before")
    void nothingToAnswer() {
        RunVerdict plain = new RunVerdict();
        plain.expecting(StepExpectations.all("1. \"Geri\" düğmesine tıkla"));
        plain.reportResult(List.of("Geri'ye basıldı"), List.of(), List.of(), List.of(), true);
        assertThat(plain.result().passed()).isTrue();

        RunVerdict code = verdict();
        code.reportResult(List.of(), List.of("Ekran koddan açılamadı."), List.of(), false);
        assertThat(code.result().failed()).containsExactly("Ekran koddan açılamadı.");
    }

    @Test
    @DisplayName("a final Beklenen about the screen Geri left fails in code, whatever the agent said")
    void finalExpectationAboutAnEarlierScreenFails() {
        RunVerdict verdict = verdict();
        verdict.reportResult(List.of(), List.of(), List.of(),
                List.of("B1 GÖRÜLDÜ: başlık", "B2 GÖRÜLDÜ: salt okunur göründü", "B3 GÖRÜLDÜ: hepsi salt okunur"),
                true);
        ExpectationCheck.apply(verdict, walk());
        RunVerdict.Result result = verdict.result();
        assertThat(result.passed()).isFalse();
        // B1 is the placeholder the scenario was run with: no page carries that "title".
        assertThat(result.failed()).hasSize(2);
        assertThat(result.failed().get(0))
                .startsWith("Beklenen (3. adımdan sonra) görülmedi: \"<güncelleme ekranının başlığı>\"");
        assertThat(result.failed().get(1))
                .startsWith("Beklenen (son ekranda) kontrol edilemedi: Kurum Adı")
                .contains("\"Kurum Adı\", \"Bayi Ünvanı\", \"Para Birimi\", \"Fatura Dönemi\" alanları son ekranda yok")
                .contains("adımın hemen altına \"Beklenen:\"");
    }

    @Test
    @DisplayName("13.11.2026: a field written wrong on purpose ('Yeni Son banko Tarihi') cannot be 'görüldü'")
    void aNameNoPageShowedFails() {
        String scenario = """
                Adımlar:
                1. İşlemler panelinden "Fatura Güncelle" bağlantısına tıkla

                Beklenen:
                - Yeni Fatura No, Yeni Para Birimi, Yeni Son banko Tarihi, Yeni Fatura Dönemi ve Yeni Fatura Tipi alanları görünür
                - Yeni Fatura No ve Yeni Fatura Tutarı alanları tamamen boş değerlerle görünür""";
        String page = UPDATE_PAGE + """

                - generic [ref=e827]: Yeni Para Birimi
                - textbox "Yeni Son Ödeme Tarihi" [ref=e848]: 13/11/2026
                - generic [ref=e858]: Yeni Fatura Tipi""";
        RunVerdict verdict = new RunVerdict();
        verdict.expecting(StepExpectations.all(scenario));
        verdict.reportResult(List.of("Tüm adımlar uygulandı"),
                List.of("B2 beklenen sonucu karşılanmadı: Yeni Fatura No dolu"), List.of(),
                List.of("B1 GÖRÜLDÜ: Yeni Fatura No, Yeni Para Birimi, Yeni Son banko Tarihi … alanları görünür",
                        "B2 GÖRÜLMEDİ: Yeni Fatura No alanı 'HLLSRC03' değeriyle dolu"), false);
        ExpectationCheck.apply(verdict, List.of(
                new TapeRenderer.Call(1, "browser_snapshot", "{}", "OK", LIST_PAGE),
                new TapeRenderer.Call(2, "browser_snapshot", "{}", "OK", page)));
        RunVerdict.Result result = verdict.result();
        assertThat(result.failed()).hasSize(2);
        assertThat(result.failed().get(0))
                .startsWith("Beklenen (son ekranda) görülmedi: Yeni Fatura No, Yeni Para Birimi")
                .contains("\"Yeni Son banko Tarihi\" adı bu koşuda hiçbir ekranda geçmedi");
        assertThat(result.failed().get(1)).startsWith("Beklenen (son ekranda) görülmedi: Yeni Fatura No ve");
        assertThat(result.succeeded()).containsExactly("Tüm adımlar uygulandı");
    }

    @Test
    @DisplayName("a lower-case word inside a name is kept; a free sentence is never read as field names")
    void namesKeepTheWholeName() {
        assertThat(ExpectationCheck.names("Yeni Son banko Tarihi ve Yeni Fatura Dönemi alanları görünür"))
                .containsExactly("Yeni Son banko Tarihi", "Yeni Fatura Dönemi");
        assertThat(ExpectationCheck.nowhere("Hata mesajı görünür: İşlem yapılamadı", List.of(LIST_PAGE)))
                .isEmpty();
        assertThat(ExpectationCheck.nowhere("Bayi Kodu ve Fatura No alanları görünür", List.of(LIST_PAGE)))
                .isEmpty();
    }

    @Test
    @DisplayName("names the last page shows, names no page showed, and 'it is gone' expectations are left alone")
    void narrowOnPurpose() {
        List<String> earlier = List.of(UPDATE_PAGE);
        assertThat(ExpectationCheck.leftBehind("Fatura No, Bayi Kodu ve Fatura Tutarı alanları görünür",
                LIST_PAGE, earlier)).isEmpty();
        assertThat(ExpectationCheck.leftBehind("Hesap No ve Şube Adı alanları görünür", LIST_PAGE, earlier))
                .isEmpty();
        assertThat(ExpectationCheck.leftBehind("Kurum Adı ve Para Birimi alanları kapanır, görünmez",
                LIST_PAGE, earlier)).isEmpty();
        assertThat(ExpectationCheck.names("\"Firma Fatura İşlemleri\" listesi yeniden görünür"))
                .containsExactly("Firma Fatura İşlemleri");
    }
}
