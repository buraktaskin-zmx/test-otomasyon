package orchestrator.validation;

import orchestrator.catalog.CatalogModel;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Searching inside a dropdown: `click` opens its list, `enter` types into the
 * list's search box.
 *
 * An `enter` on a dropdown is the wrong verb ON ITS OWN — a scenario meaning
 * to choose an option would type a search, choose nothing and pass. Right
 * after a `click` on the same label it is a search, and the scenario says so.
 */
class OpenedDropdownSearchTest {

    private final ScenarioValidator validator = new ScenarioValidator();

    private static String scenario(String... steps) {
        StringBuilder out = new StringBuilder("""
                Feature: Firma Fatura İşlemleri

                  Scenario: Firma Seçimi listesinde arama yapılır
                    Given navigate to "NAKİT YÖNETİMİ" "Firma Fatura İşlemleri"
                """);
        for (String step : steps) {
            out.append("    And ").append(step).append('\n');
        }
        out.append("    Then verify \"Sanko DBS Ödemeleri\" text is displayed\n");
        return out.toString();
    }

    @Test
    @DisplayName("enter right after a click on the same dropdown is a search, and valid")
    void searchesTheListTheClickOpened() {
        ValidationResult result = validator.validate(
                scenario("click \"Firma Seçimi\"", "enter \"Firma Seçimi\" \"san\""), vocabulary(), catalog());

        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("enter on a dropdown with no click before it is still the wrong verb")
    void aLoneEnterIsStillRefused() {
        ValidationResult result = validator.validate(
                scenario("enter \"Firma Seçimi\" \"san\""), vocabulary(), catalog());

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.kind()).isEqualTo(ValidationError.Kind.WRONG_VERB));
    }

    @Test
    @DisplayName("the click has to be the step right before; another step in between closes the case")
    void onlyTheStepRightAfterTheClick() {
        ValidationResult result = validator.validate(
                scenario("click \"Firma Seçimi\"", "click \"Listele\"", "enter \"Firma Seçimi\" \"san\""),
                vocabulary(), catalog());

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.kind()).isEqualTo(ValidationError.Kind.WRONG_VERB));
    }

    @Test
    @DisplayName("a catalogued option may be asserted as displayed; an invented sentence still may not")
    void acceptsWhatTheCatalogSaysIsPrintedOnly() {
        String invented = scenario("click \"Firma Seçimi\"", "enter \"Firma Seçimi\" \"san\"")
                .replace("verify \"Sanko DBS Ödemeleri\" text is displayed",
                        "verify \"Arama sonucu bulundu\" text is displayed");

        ValidationResult result = validator.validate(invented, vocabulary(), catalog());

        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.kind()).isEqualTo(ValidationError.Kind.UNCATALOGUED_MESSAGE));
    }

    /* ----------------------------------------------------------------- */

    private static CatalogModel catalog() {
        return CatalogModel.parse("""
                app: NGC
                flow: Firma Fatura Islemleri
                fields:
                  - label: "Firma Seçimi"
                    type: dropdown
                    options: ["Sanko DBS Ödemeleri", "APPLE A.Ş."]
                """).orElseThrow();
    }

    private static Vocabulary vocabulary() {
        return new Vocabulary(List.of(
                        "navigate to {string} {string}",
                        "click {string}",
                        "enter {string} {string}",
                        "select {string} from {string}",
                        "verify {string} text is displayed").stream()
                .map(OpenedDropdownSearchTest::step)
                .toList());
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w*)}");

    private static VocabularyStep step(String pattern) {
        List<String> parameters = PLACEHOLDER.matcher(pattern).results()
                .map(match -> match.group(1))
                .toList();
        String keyword = pattern.startsWith("verify") ? "Then" : "And";
        return new VocabularyStep(pattern, keyword, parameters, "CommonSteps", "step");
    }
}
