package orchestrator.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reading half of the product layer, pinned where it is lenient and where
 * it refuses to be.
 *
 * Lenient is the whole design, copied from {@code CatalogModel}: this is not a
 * schema validator and must never become one, because the file is written by
 * an analyst and a half-finished rule book has to keep working. What leniency
 * must NOT do is swallow a mistake without trace — hence the duplicate-id
 * report.
 */
class ProductModelTest {

    private static final String IDENTITY = """
            app: NGC
            name: "Maaş Ödemeleri"
            flows:
              - maas-odeme-olustur
              - maas-odeme-onay
            """;

    @Test
    @DisplayName("a product with no rules yet still loads")
    void rulesAreOptional() {
        // The ordinary state of a product someone started this morning. If
        // this failed, the rules-before-screens order the whole layer exists
        // for would be impossible to begin.
        ProductModel product = parse(IDENTITY, "").orElseThrow();

        assertThat(product.name()).isEqualTo("Maaş Ödemeleri");
        assertThat(product.flows()).containsExactly("maas-odeme-olustur", "maas-odeme-onay");
        assertThat(product.rules()).isEmpty();
        assertThat(product.messages()).isEmpty();
    }

    @Test
    @DisplayName("an unreadable rules file costs the rules, not the product")
    void brokenRulesDoNotKillTheProduct() {
        ProductModel product = parse(IDENTITY, "rules: [ this is not: valid: yaml").orElseThrow();

        assertThat(product.rules()).isEmpty();
        assertThat(ProductModel.diagnoseRules("rules: [ this is not: valid: yaml"))
                .contains("sözdizimi hatalı");
    }

    @Test
    @DisplayName("without product.yaml there is no product")
    void identityIsLoadBearing() {
        assertThat(parse("", "rules: []")).isEmpty();
        assertThat(parse("just a sentence, not a map", "")).isEmpty();
    }

    @Test
    @DisplayName("an unverified rule is not confirmed, and neither is one with no status")
    void assumptionsAreNotPromoted() {
        ProductModel product = parse(IDENTITY, """
                rules:
                  - id: R-1
                    status: DOĞRULANDI
                  - id: R-2
                    status: VARSAYIM
                  - id: R-3
                """).orElseThrow();

        // R-3 is the one that matters: a missing status must read as "nobody
        // checked", never as "verified". Defaulting the other way would let a
        // guess into a test with nothing on screen saying so.
        assertThat(product.rules()).extracting(Rule::id, Rule::confirmed)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("R-1", true),
                        org.assertj.core.groups.Tuple.tuple("R-2", false),
                        org.assertj.core.groups.Tuple.tuple("R-3", false));
    }

    @Test
    @DisplayName("a repeated id keeps the first rule and says so")
    void duplicatesAreReportedRatherThanLost() {
        ProductModel product = parse(IDENTITY, """
                rules:
                  - id: R-1
                    title: "ilk"
                  - id: R-1
                    title: "ikinci"
                """).orElseThrow();

        assertThat(product.rules()).hasSize(1);
        assertThat(product.rules().get(0).title()).isEqualTo("ilk");
        // Without this the second rule would simply not exist, and the analyst
        // would be reading a file whose contents disagree with the UI.
        assertThat(product.duplicateIds()).containsExactly("R-1");
    }

    @Test
    @DisplayName("a rule with no id is dropped — it could never be selected or reported on")
    void idIsRequired() {
        ProductModel product = parse(IDENTITY, """
                rules:
                  - title: "kimliksiz"
                  - id: R-2
                    title: "kimlikli"
                """).orElseThrow();

        assertThat(product.rules()).extracting(Rule::id).containsExactly("R-2");
    }

    @Test
    @DisplayName("messages are gathered across rules, deduplicated, in declaration order")
    void messagesAreTheUnion() {
        ProductModel product = parse(IDENTITY, """
                rules:
                  - id: R-1
                    messages:
                      - "Geçersiz IBAN numarası girdiniz."
                      - "Tutar sıfır olamaz."
                  - id: R-2
                    messages:
                      - "Tutar sıfır olamaz."
                """).orElseThrow();

        assertThat(product.messages())
                .containsExactly("Geçersiz IBAN numarası girdiniz.", "Tutar sıfır olamaz.");
    }

    @Test
    @DisplayName("selection keeps declaration order and ignores an id it does not know")
    void selectionIsForgiving() {
        ProductModel product = parse(IDENTITY, """
                rules:
                  - id: R-1
                  - id: R-2
                  - id: R-3
                """).orElseThrow();

        // R-9 is a browser one rules-file edit behind. Losing one rule beats
        // refusing to generate anything.
        assertThat(product.selected(List.of("R-3", "R-9", "R-1")))
                .extracting(Rule::id).containsExactly("R-1", "R-3");
        assertThat(product.selected(List.of())).isEmpty();
        assertThat(product.selected(null)).isEmpty();
    }

    private static java.util.Optional<ProductModel> parse(String identity, String rules) {
        return ProductModel.parse("ngc", "maas-odemeleri", ProductModel.Sources.of(identity, rules));
    }
}
