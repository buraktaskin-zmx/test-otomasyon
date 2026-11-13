package orchestrator.explore;

import orchestrator.persistence.AiRunCacheEntity;
import orchestrator.persistence.AiRunCacheRepository;
import orchestrator.product.ProductService;
import orchestrator.product.ScenarioStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 13.11.2026, the analyst's rule: a cached flow is bound to the STEPS, with
 * their quoted values as parameters — a new title, precondition or Beklenen
 * keeps "Hızlı çalıştır" on.
 */
class ScenarioShapeTest {

    private static String text(String title, String firm, String expected) {
        return "NAKİT YÖNETİMİ menüsü altındaki \"Firma Fatura İşlemleri\" ekranına git.\n"
                + "Sonra şu senaryoyu uygula:\n\n"
                + "Senaryo adı (yalnızca bir isim, yapılacak bir adım DEĞİL): " + title + "\n\n"
                + "Ön koşul:\n- " + firm + " firmasında Ödenecek fatura bulunabilmeli\n\n"
                + "Adımlar — numara sırasıyla:\n"
                + "1. \"Firma Seçimi\" listesinden \"" + firm + "\" seç\n"
                + "2. Ödeme Durumu \"Ödenecek\" olan ilk satırı seç\n"
                + "3. İşlemler panelinden \"Fatura Güncelle\" bağlantısına tıkla\n"
                + "   Beklenen (bu adımdan hemen sonra kontrol et): \"Fatura Güncelle\" ekranı açılır\n\n"
                + "Beklenen:\n- " + expected;
    }

    private static final String SAVED = text("Güncelleme formu", "ASAŞ ALÜMİNYUM SAN.", "Yeni alanlar görünür");

    private static final List<ReplayCalls.ReplayCall> CALLS = List.of(
            new ReplayCalls.ReplayCall("browser_click", "internal:role=textbox[name=\"Seçiniz\"i]", "{}", null,
                    List.of("Seçiniz"), "tıkla \"Seçiniz\"", null),
            new ReplayCalls.ReplayCall("browser_click", "internal:role=option[name=\"ASAŞ ALÜMİNYUM SAN.\"i]", "{}",
                    null, List.of("ASAŞ ALÜMİNYUM SAN."), "tıkla \"ASAŞ ALÜMİNYUM SAN.\"", null),
            new ReplayCalls.ReplayCall("browser_click",
                    "internal:role=row[name=\"103212 HLLSRC03 10/11/2026 25\"i] >> internal:role=radio", "{}", null,
                    List.of("103212 HLLSRC03 10/11/2026 25"), "tıkla \"103212 HLLSRC03 10/11/2026 25\"", null),
            new ReplayCalls.ReplayCall("browser_click", "internal:role=link[name=\"Fatura Güncelle\"i]", "{}", null,
                    List.of("Fatura Güncelle"), "tıkla \"Fatura Güncelle\"", null));

    @Test
    @DisplayName("title, precondition and Beklenen do not change the shape; a step's own words do")
    void shapeIsTheSteps() {
        String edited = text("Başka bir başlık", "ASAŞ ALÜMİNYUM SAN.", "Tamamen başka bir Beklenen");
        assertThat(ScenarioShape.shape(edited)).isEqualTo(ScenarioShape.shape(SAVED));
        assertThat(ScenarioShape.shape(text("x", "SANKO DBS ÖDEMELERİ", "y")))
                .isEqualTo(ScenarioShape.shape(SAVED));
        String otherAction = SAVED.replace("bağlantısına tıkla", "bağlantısını aç");
        assertThat(ScenarioShape.shape(otherAction)).isNotEqualTo(ScenarioShape.shape(SAVED));
        assertThat(ScenarioShape.shape("düz metin, numaralı adım yok")).isNull();
    }

    @Test
    @DisplayName("a changed firm is put into the option's selector; nothing else moves")
    void aChangedValueIsPutIn() {
        String current = text("Güncelleme formu", "SANKO DBS ÖDEMELERİ", "Yeni alanlar görünür");
        Map<String, String> renames = ScenarioShape.renames(SAVED, current);
        assertThat(renames).containsExactly(Map.entry("ASAŞ ALÜMİNYUM SAN.", "SANKO DBS ÖDEMELERİ"));
        ScenarioShape.Renamed renamed = ScenarioShape.rename(CALLS, renames);
        assertThat(renamed.unplaced()).isEmpty();
        assertThat(renamed.calls().get(1).target()).isEqualTo("internal:role=option[name=\"SANKO DBS ÖDEMELERİ\"i]");
        assertThat(renamed.calls().get(1).says()).isEqualTo("tıkla \"SANKO DBS ÖDEMELERİ\"");
        assertThat(renamed.calls().get(0).target()).isEqualTo(CALLS.get(0).target());
    }

    @Test
    @DisplayName("a typed search value is renamed in the text argument, never inside a longer string")
    void typedValues() {
        List<ReplayCalls.ReplayCall> typed = List.of(new ReplayCalls.ReplayCall(FocusedTyping.TOOL, null,
                "{\"text\":\"san\"}", null, List.of("san"), "yaz \"san\"", null),
                new ReplayCalls.ReplayCall("browser_click", "internal:role=option[name=\"ASAŞ ALÜMİNYUM SAN.\"i]",
                        "{}", null, List.of("ASAŞ ALÜMİNYUM SAN."), "tıkla", null));
        ScenarioShape.Renamed renamed = ScenarioShape.rename(typed, Map.of("san", "ford"));
        assertThat(renamed.calls().get(0).args()).isEqualTo("{\"text\":\"ford\"}");
        assertThat(renamed.calls().get(1).target()).contains("ASAŞ ALÜMİNYUM SAN.");
        assertThat(renamed.unplaced()).isEmpty();
    }

    @Test
    @DisplayName("a changed value no recorded target carries cannot be honoured: the row text is not 'Ödenecek'")
    void aValueWithNoPlaceSwitchesTheFlowOff() {
        String current = SAVED.replace("\"Ödenecek\"", "\"Ödendi\"");
        ScenarioShape.Renamed renamed = ScenarioShape.rename(CALLS, ScenarioShape.renames(SAVED, current));
        assertThat(renamed.unplaced()).containsExactly("\"Ödenecek\" → \"Ödendi\"");
    }

    @Test
    @DisplayName("through AiRunCache: an edited title/Beklenen stays available; a new step Beklenen is attached")
    void cacheStatusFollowsTheSteps() {
        UUID id = UUID.randomUUID();
        AiRunCacheRepository rows = mock(AiRunCacheRepository.class);
        ProductService products = mock(ProductService.class);
        when(products.scenario(any(), any(), any())).thenReturn(Optional.of(
                new ScenarioStore.Stored(id, 1, "firma-fatura-islemleri", "55.feature", null, null, List.of(), null,
                        null)));
        AiRunCacheEntity row = new AiRunCacheEntity(id);
        row.store("firma-fatura-islemleri/55.feature", AiRunCache.keyOf(SAVED), SAVED, ReplayCalls.toJson(CALLS),
                CALLS.size(), "tape", "QZ", Instant.now());
        when(rows.findById(id)).thenReturn(Optional.of(row));
        AiRunCache cache = new AiRunCache(rows, products);

        String edited = text("Yeni başlık", "SANKO DBS ÖDEMELERİ", "Başka bir Beklenen");
        AiRunCache.Status status = cache.status("ngc", "nakit", "firma-fatura-islemleri/55.feature", edited);
        assertThat(status.available()).isTrue();
        AiRunCache.Visit visit = cache.lookup("ngc", "nakit", "firma-fatura-islemleri/55.feature", edited, "QZ");
        assertThat(visit.calls.get(1).target()).contains("SANKO DBS ÖDEMELERİ");
        assertThat(visit.calls.get(3).expected()).isEqualTo("\"Fatura Güncelle\" ekranı açılır");

        String newStep = edited.replace("2. Ödeme Durumu", "2. Detaylı Arama'yı aç ve Ödeme Durumu");
        assertThat(cache.status("ngc", "nakit", "firma-fatura-islemleri/55.feature", newStep).reason())
                .startsWith("adımlar kayıttan sonra değişmiş");
    }

    @Test
    @DisplayName("a row written before 13.11.2026 (no saved text) is held to its whole-text hash, as before")
    void legacyRows() {
        UUID id = UUID.randomUUID();
        AiRunCacheRepository rows = mock(AiRunCacheRepository.class);
        ProductService products = mock(ProductService.class);
        when(products.scenario(any(), any(), any())).thenReturn(Optional.of(
                new ScenarioStore.Stored(id, 1, "f", "a.feature", null, null, List.of(), null, null)));
        AiRunCacheEntity row = new AiRunCacheEntity(id);
        row.store("f/a.feature", AiRunCache.sha256(SAVED), null, ReplayCalls.toJson(CALLS), CALLS.size(), "t", "QZ",
                Instant.now());
        when(rows.findById(id)).thenReturn(Optional.of(row));
        AiRunCache cache = new AiRunCache(rows, products);
        assertThat(cache.status("ngc", "p", "f/a.feature", SAVED).available()).isTrue();
        assertThat(cache.status("ngc", "p", "f/a.feature", SAVED + " ").available()).isTrue();
        assertThat(cache.status("ngc", "p", "f/a.feature", SAVED.replace("Güncelleme formu", "x")).available())
                .isFalse();
    }
}
