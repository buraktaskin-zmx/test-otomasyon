package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimCheckTest extends TapeFixture {

    /** The text the UI sends for "İptal ekranından Geri ile listeye dönülür" (docAsText). */
    private static final String SCENARIO = """
            NAKİT YÖNETİMİ menüsü altındaki "Firma Fatura İşlemleri" ekranına git.
            Sonra şu senaryoyu uygula:

            Senaryo adı (yalnızca bir isim, yapılacak bir adım DEĞİL): İptal ekranından Geri ile listeye dönülür

            Ön koşul:
            - ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli

            Adımlar — numara sırasıyla, birer birer uygula. Bir adım bitmeden sonrakine geçme; sonraki bir adımın butonuna önceden basma; burada yazmayan bir butona basma:
            1. Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç
            2. Listede Ödeme Durumu "Ödenecek" olan ilk faturanın satırını seç
            3. İşlemler panelindeki "Fatura İptal Et" düğmesine tıkla
            4. Açılan ekranda "Geri" düğmesine tıkla

            Beklenen:
            - Liste ekranına dönülür
            - Seçilen faturanın Ödeme Durumu "Ödenecek" olarak kalır""";

    private static final List<String> CLAIMED = List.of(
            "Firma seçimi yapıldı",
            "Ödenecek durumundaki ilk fatura satırı seçildi",
            "Fatura İptal Et düğmesine tıklandı",
            "Geri düğmesine tıklandı ve liste ekranına dönüldü",
            "Seçilen faturanın ödeme durumu hala Ödenecek olarak görünüyor");

    /** The 12.11.2026 walk as far as the log shows it: the firm, then radio clicks, nothing else. */
    private void theWalkThatWasReportedGreen() {
        ok("browser_click", "{\"target\": \"f3e423\"}",
                ran("await page.getByRole('combobox', { name: 'Seçiniz' }).click();"));
        ok("browser_click", "{\"target\": \"f3e493\"}",
                ran("await page.getByRole('option', { name: 'ASAŞ ALÜMİNYUM SAN.' }).click();"));
        ok("browser_click", "{\"target\": \"f3e664\"}", ran("await page.getByRole('radio').nth(0).click();"));
        ok("browser_click", "{\"target\": \"f3e672\"}", ran("await page.getByRole('radio').nth(1).click();"));
        ok("browser_click", "{\"target\": \"f3e703\"}", ran("await page.getByRole('radio').nth(2).click();"));
    }

    @Test
    @DisplayName("only the named presses are demanded: the quoted value in step 2 is not")
    void readsThePressesOfTheScenario() {
        assertThat(ClaimCheck.pressesAskedFor(SCENARIO)).containsExactly("Fatura İptal Et", "Geri");
    }

    @Test
    @DisplayName("a value quoted before the pressed name is not the thing pressed")
    void lastQuotedNameBeforeTheVerb() {
        assertThat(ClaimCheck.pressesAskedFor("1. \"Ödenecek\" olan satırdaki \"Detay\" bağlantısına tıklayın"))
                .containsExactly("Detay");
    }

    @Test
    @DisplayName("a step saying what NOT to press demands nothing")
    void negativesAreNotPresses() {
        assertThat(ClaimCheck.pressesAskedFor("""
                1. "Ödenecek" olan ilk satırı seç, başka satıra tıklama
                2. "Onayla" düğmesine basma
                3. "Geri" düğmesine tıklayın""")).containsExactly("Geri");
    }

    @Test
    @DisplayName("12.11.2026: every step claimed, neither button on the tape -> Geçmedi")
    void theReportedGreenWalkIsRed() {
        theWalkThatWasReportedGreen();
        RunVerdict verdict = new RunVerdict();
        verdict.reportResult(CLAIMED, List.of(), List.of(), true);

        ClaimCheck.apply(verdict, SCENARIO, calls, List.of());

        RunVerdict.Result result = verdict.result();
        assertThat(result.passed()).isFalse();
        assertThat(result.failed()).hasSize(2)
                .anySatisfy(line -> assertThat(line).startsWith("\"Fatura İptal Et\""))
                .anySatisfy(line -> assertThat(line).startsWith("\"Geri\""));
        assertThat(result.succeeded())
                .doesNotContain("Fatura İptal Et düğmesine tıklandı",
                        "Geri düğmesine tıklandı ve liste ekranına dönüldü")
                .contains("Firma seçimi yapıldı");
        assertThat(verdict.toJson()).containsEntry("verdict", "FAILED");
    }

    @Test
    @DisplayName("İptal Et pressed, Geri not -> only Geri is refuted")
    void onlyTheMissingPress() {
        theWalkThatWasReportedGreen();
        ok("browser_click", "{\"target\": \"f3e720\"}",
                ran("await page.getByRole('button', { name: 'Fatura İptal Et' }).click();"));

        assertThat(ClaimCheck.unclicked(SCENARIO, calls, List.of())).containsExactly("Geri");
    }

    @Test
    @DisplayName("both pressed -> the agent's green stands")
    void bothPressedStaysGreen() {
        theWalkThatWasReportedGreen();
        ok("browser_click", "{\"target\": \"f3e720\"}",
                ran("await page.getByRole('button', { name: 'Fatura İptal Et' }).click();"));
        ok("browser_click", "{\"target\": \"f4e12\"}",
                ran("await page.getByRole('link', { name: 'Geri', exact: true }).click();"));
        RunVerdict verdict = new RunVerdict();
        verdict.reportResult(CLAIMED, List.of(), List.of(), true);

        ClaimCheck.apply(verdict, SCENARIO, calls, List.of());

        assertThat(verdict.result().passed()).isTrue();
        assertThat(verdict.result().succeeded()).hasSize(5);
    }

    @Test
    @DisplayName("a click the renderer resolved from the snapshot counts, even when the echoed line names nothing")
    void renderedClickCounts() {
        ok("browser_click", "{\"target\": \"f4e12\"}", ran("await page.locator('#btnGeri').click();"));

        assertThat(ClaimCheck.unclicked("1. \"Geri\" düğmesine tıkla", calls, List.of("click \"Geri\"")))
                .isEmpty();
    }

    @Test
    @DisplayName("a refused or failed click does not count")
    void failedClickDoesNotCount() {
        failed("browser_click", "{\"target\": \"f4e12\"}",
                "### Error\nawait page.getByRole('button', { name: 'Geri' }).click();");

        assertThat(ClaimCheck.unclicked("1. \"Geri\" düğmesine tıkla", calls, List.of()))
                .containsExactly("Geri");
    }

    @Test
    @DisplayName("tape 2628c4fd as recorded: doubly numbered steps, the real click lines -> both presses refuted")
    void theRecordedTape() {
        String recorded = """
                Adımlar — numara sırasıyla, birer birer uygula. Bir adım bitmeden sonrakine geçme; sonraki bir adımın butonuna önceden basma; burada yazmayan bir butona basma:
                1. Adımlar
                2. 1. Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç
                3. 2. Listede Ödeme Durumu "Ödenecek" olan ilk faturanın satırını seç
                4. 3. İşlemler panelindeki "Fatura İptal Et" düğmesine tıkla
                5. 4. Açılan ekranda "Geri" düğmesine tıkla""";
        ok("browser_click", "{\"target\": \"f3e423\"}",
                ran("await page.getByRole('textbox', { name: 'Seçiniz' }).click();"));
        ok("browser_click", "{\"target\": \"f3e493\"}",
                ran("await page.getByRole('option', { name: 'ASAŞ ALÜMİNYUM SAN.' }).click();"));
        ok("browser_click", "{\"target\": \"f3e664\"}",
                ran("await page.getByRole('row', { name: '103212 HLLSRC03 10/11/2026 25' }).click();"));
        ok("browser_click", "{\"target\": \"f3e672\"}",
                ran("await page.getByRole('cell', { name: 'Ödenecek' }).first().click();"));
        ok("browser_click", "{\"target\": \"f3e703\"}",
                ran("await page.getByRole('cell', { name: '103212' }).nth(5).click();"));

        assertThat(ClaimCheck.pressesAskedFor(recorded)).containsExactly("Fatura İptal Et", "Geri");
        assertThat(ClaimCheck.unclicked(recorded, calls, List.of())).containsExactly("Fatura İptal Et", "Geri");
    }

    @Test
    @DisplayName("Serbest Gözlem has no verdict and is left alone")
    void noVerdictNoCheck() {
        ClaimCheck.apply(null, SCENARIO, calls, List.of());
    }
}
