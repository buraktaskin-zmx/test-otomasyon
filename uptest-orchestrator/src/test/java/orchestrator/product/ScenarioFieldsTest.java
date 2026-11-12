package orchestrator.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioFieldsTest {

    @Test
    @DisplayName("12.11.2026: a pasted block's own heading and numbers leave the steps")
    void pastedHeadingAndNumbersGo() {
        ScenarioFields fields = new ScenarioFields("İptal ekranından Geri ile listeye dönülür",
                List.of(), List.of(), List.of(),
                List.of("Ön koşul:", "ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli"),
                List.of("Adımlar",
                        "1. Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç",
                        "2. Listede Ödeme Durumu \"Ödenecek\" olan ilk faturanın satırını seç",
                        "3) İşlemler panelindeki \"Fatura İptal Et\" düğmesine tıkla",
                        "- 4. Açılan ekranda \"Geri\" düğmesine tıkla"),
                List.of("Beklenen:", "Liste ekranına dönülür"),
                "");

        assertThat(fields.steps()).containsExactly(
                "Firma Seçimi alanından ASAŞ ALÜMİNYUM SAN. seç",
                "Listede Ödeme Durumu \"Ödenecek\" olan ilk faturanın satırını seç",
                "İşlemler panelindeki \"Fatura İptal Et\" düğmesine tıkla",
                "Açılan ekranda \"Geri\" düğmesine tıkla");
        assertThat(fields.preconditions())
                .containsExactly("ASAŞ ALÜMİNYUM SAN. firmasında Ödenecek durumunda fatura bulunabilmeli");
        assertThat(fields.expected()).containsExactly("Liste ekranına dönülür");
    }

    @Test
    @DisplayName("a step that merely begins with a number or mentions a heading word is kept")
    void ordinaryTextIsKept() {
        ScenarioFields fields = new ScenarioFields("x", List.of("R-1"), List.of(), List.of(), List.of(),
                List.of("3 adet fatura seç", "Adımlar bölümündeki ilk satırı seç", "2026 yılını seç"),
                List.of(), "");

        assertThat(fields.steps())
                .containsExactly("3 adet fatura seç", "Adımlar bölümündeki ilk satırı seç", "2026 yılını seç");
        assertThat(fields.rules()).containsExactly("R-1");
    }
}
