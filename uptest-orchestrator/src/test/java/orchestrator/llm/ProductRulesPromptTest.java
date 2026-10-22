package orchestrator.llm;

import orchestrator.product.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the product rules look like when they reach the model, and — just as
 * important — what the prompt looks like when there are none.
 *
 * The second half is the one worth a test. This prompt already carries eleven
 * hard rules, the live step list and the whole catalog, and this project has
 * measured what distance does to an instruction: "the prompt is read ONCE, and
 * by field eleven it is far away". A product block that appeared on every
 * generation, empty header and all, would be pure dilution of the part that
 * works.
 */
class ProductRulesPromptTest {

    private static final String CATALOG = """
            app: NGC
            flow: Maas Odemeleri
            fields:
              - label: "Açıklama"
                type: text
            """;

    private final PromptBuilder prompts = new PromptBuilder();

    @Test
    @DisplayName("no rules means no block — not an empty heading")
    void nothingIsAddedWhenNothingWasTicked() {
        String withoutRules = prompts.user(CATALOG, "maaş dosyası yükle", null, null, List.of());

        assertThat(withoutRules).doesNotContain("ÜRÜN KURALLARI");
        // And byte-identical to the call that existed before the product layer,
        // so an analyst who selects no product gets exactly the prompt that has
        // been generating scenarios all along.
        assertThat(withoutRules).isEqualTo(prompts.user(CATALOG, "maaş dosyası yükle", null));
    }

    @Test
    @DisplayName("a ticked rule arrives with its id, its words and its exact message")
    void aRuleCarriesWhatTheModelNeeds() {
        String prompt = prompts.user(CATALOG, "hatalı IBAN testi", null, null, List.of(ibanRule()));

        assertThat(prompt).contains("ÜRÜN KURALLARI");
        assertThat(prompt).contains("[R-MAAS-010]");
        assertThat(prompt).contains("IBAN format kontrolü");
        assertThat(prompt).contains("Geçersiz IBAN numarası girdiniz.");
        // The message has to be copied, not improved: the validator compares it
        // character for character, so a tidier rewording is a scenario that
        // cannot pass.
        assertThat(prompt).contains("BİREBİR");
    }

    @Test
    @DisplayName("the rules sit between the screen and the task")
    void orderIsTheArgument() {
        String prompt = prompts.user(CATALOG, "hatalı IBAN testi", null, null, List.of(ibanRule()));

        // Catalog gives the nouns, rules say what is true of them, and only
        // then does the request say which of it to exercise. Placed after the
        // intent the block reads as an afterthought to a task already set.
        assertThat(prompt.indexOf("SCREEN CATALOG"))
                .isLessThan(prompt.indexOf("ÜRÜN KURALLARI"));
        assertThat(prompt.indexOf("ÜRÜN KURALLARI"))
                .isLessThan(prompt.indexOf("WHAT TO TEST"));
    }

    @Test
    @DisplayName("an unverified rule says so in the prompt")
    void anAssumptionIsLabelled() {
        Rule guess = new Rule("R-MAAS-034", "Kısmi hatalı dosya", Rule.ASSUMPTION,
                "Hatalı satırlar ayıklanır, kalanı devam eder.", List.of(), List.of(),
                Map.of(), "");

        assertThat(prompts.user(CATALOG, "kısmi hata", null, null, List.of(guess)))
                .contains("VARSAYIM");
    }

    @Test
    @DisplayName("a rule that was not ticked does not reach the model")
    void selectionIsWhatTravels() {
        // The analyst's ticks are the filter, and this is where that has to be
        // visible: the builder is handed a list, never a product to read a
        // rule book out of.
        String prompt = prompts.user(CATALOG, "hatalı IBAN testi", null, null, List.of(ibanRule()));

        assertThat(prompt).doesNotContain("R-MAAS-034");
    }

    private static Rule ibanRule() {
        return new Rule("R-MAAS-010", "IBAN format kontrolü", Rule.CONFIRMED,
                "Dosyadaki bir satırda IBAN TR ile başlamıyorsa satır reddedilir.",
                List.of("IBAN"), List.of("Geçersiz IBAN numarası girdiniz."),
                Map.of(), "Analiz dokümanı v2.3");
    }
}
