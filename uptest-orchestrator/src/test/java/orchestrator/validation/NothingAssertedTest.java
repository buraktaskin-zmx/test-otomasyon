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
 * A scenario that checks nothing must not validate.
 *
 * This is a real draft, reproduced verbatim. Asked to open a Banko payment
 * record and verify the recipient account was masked, the model first wrote
 * {@code verify the "Alıcı Hesabı" has amount "*"} — the nearest value-shaped
 * verb, with an invented value, because no step existed for "is it masked?".
 * checkAssertionsAreEarned refused the invented value, correctly. The repair
 * turn then took the only other route to "valid" and DELETED the assertion,
 * and what came back was five steps that log in, open the record and stop:
 * valid, green, and incapable of ever failing.
 *
 * Two rules now hold each other up. One says an assertion may not be invented;
 * this one says there has to BE one.
 */
class NothingAssertedTest {

    /** The draft as the model actually returned it — navigation, no checks. */
    private static final String WALKS_THERE_AND_STOPS = """
            Feature: Nakit Yönetimi Banko İşlemleri Ödeme Detay

              Scenario: 10100500 referans numaralı kaydın alıcı hesabını doğrula
                Given login with cif "DI00002018" and userid "1335"
                And navigate to "NAKİT YÖNETİMİ" "Banko İşlemleri"
                And click "Yanıt Bekleyenler"
                And select row "10100500"
                And click "Detay"
            """;

    private final ScenarioValidator validator = new ScenarioValidator();

    @Test
    @DisplayName("a scenario with no verify step is rejected")
    void aScenarioThatChecksNothingIsNotATest() {
        ValidationResult result = validator.validate(WALKS_THERE_AND_STOPS, full());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(error ->
                assertThat(error.message()).contains("hicbir sey dogrulamiyor"));
    }

    @Test
    @DisplayName("one value-free assertion is enough to make it a test")
    void aMaskCheckIsEnough() {
        String withCheck = WALKS_THERE_AND_STOPS
                + "    Then verify the \"Alıcı Hesabı\" field is masked\n";

        assertThat(validator.validate(withCheck, full()).valid()).isTrue();
    }

    @Test
    @DisplayName("the way out is only offered when this executor actually serves it")
    void suggestionsAreFilteredByTheLiveVocabulary() {
        // The orchestrator and the executors restart separately. Suggesting a
        // step the running executor does not publish is the date-verb mistake
        // again: it turns a reported problem into "Tanımsız adım".
        ValidationResult behind = validator.validate(WALKS_THERE_AND_STOPS, withoutMaskStep());

        assertThat(behind.valid()).isFalse();
        assertThat(behind.errors()).anySatisfy(error -> {
            assertThat(error.message()).contains("hicbir sey dogrulamiyor");
            assertThat(error.suggestions()).noneMatch(s -> s.contains("is masked"));
            assertThat(error.suggestions()).contains("verify {string} text is displayed");
        });
    }

    @Test
    @DisplayName("on a read-only screen the rule stays silent when the executor offers no way out")
    void noRuleWithoutAnEscape() {
        // Every value-bearing assertion is already refused here (nothing is
        // filled in), so with no value-free assertion published there is
        // NOTHING the model could write. Raising the error would turn a
        // reported problem into an unproducible scenario — which is exactly
        // what happened on the run that prompted this: the rule shipped before
        // the step it depends on.
        ValidationResult result = validator.validate(
                WALKS_THERE_AND_STOPS, noValueFreeAssertions(), readOnlyScreen());

        assertThat(result.errors())
                .noneMatch(error -> error.message().contains("hicbir sey dogrulamiyor"));
    }

    @Test
    @DisplayName("but it still fires on a read-only screen once there IS a way out")
    void theGuardDoesNotOverSuppress() {
        ValidationResult result = validator.validate(
                WALKS_THERE_AND_STOPS, full(), readOnlyScreen());

        assertThat(result.errors())
                .anyMatch(error -> error.message().contains("hicbir sey dogrulamiyor"));
    }

    /* ----------------------------------------------------------------- */

    /** A detail screen: one field, readonly, nothing to fill in. */
    private static CatalogModel readOnlyScreen() {
        return CatalogModel.parse("""
                app: NGC
                flow: Odeme Detay
                fields:
                  - label: "Alıcı Hesabı"
                    type: text
                    readonly: true
                    verifiable: true
                """).orElseThrow();
    }

    /** An executor serving no assertion that works without a fill. */
    private static Vocabulary noValueFreeAssertions() {
        return vocabulary(NAVIGATION_STEPS + "|verify the {string} field equals {string}");
    }

    private static Vocabulary full() {
        return vocabulary(NAVIGATION_STEPS
                + "|verify the {string} field is masked"
                + "|verify {string} text is displayed");
    }

    /** An executor built before the mask step existed. */
    private static Vocabulary withoutMaskStep() {
        return vocabulary(NAVIGATION_STEPS + "|verify {string} text is displayed");
    }

    private static final String NAVIGATION_STEPS =
            "login with cif {string} and userid {string}"
                    + "|navigate to {string} {string}"
                    + "|click {string}"
                    + "|select row {string}";

    private static Vocabulary vocabulary(String patterns) {
        return new Vocabulary(List.of(patterns.split("\\|")).stream()
                .map(NothingAssertedTest::step)
                .toList());
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w*)}");

    private static VocabularyStep step(String pattern) {
        List<String> parameters = PLACEHOLDER.matcher(pattern).results()
                .map(match -> match.group(1))
                .toList();
        String keyword = pattern.startsWith("verify") ? "Then"
                : pattern.startsWith("login") ? "Given" : "And";
        return new VocabularyStep(pattern, keyword, parameters, "CommonSteps", "step");
    }
}
