package orchestrator.llm;

import orchestrator.catalog.CatalogModel;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rewriter that writes the one assertion a read-only screen allows.
 *
 * Most of these tests are about it staying OUT of the way. That is the point:
 * it adds a step the model never wrote, so the cost of firing wrongly is an
 * assertion about the wrong field in someone's test, while the cost of not
 * firing is the validator's existing complaint. The two are not symmetrical.
 */
class ReadOnlyAssertionTest {

    /** The draft the model actually returned: navigation, no checks. */
    private static final String NO_ASSERTION = """
            Feature: Alıcı hesabının yıldızlı gelmesi

              Scenario: Alıcı hesabının yıldızlı olarak görüntülendiğini doğrula
                Given login with cif "DI00002018" and userid "1335"
                And navigate to "NAKİT YÖNETİMİ" "Banko İşlemleri"
                And click "Yanıt Bekleyenler"
                And select row "10100500"
                And click "Detay"
            """;

    /** The analyst's own words, mixing Turkish and ASCII as they really do. */
    private static final String ASKED = "alıcı hesabının yıldızlı geldigini dogrula";

    @Test
    @DisplayName("writes the masked check for the field the request names")
    void writesTheAssertion() {
        String out = ReadOnlyAssertion.apply(NO_ASSERTION, detailScreen(), serving(), ASKED);

        assertThat(out).contains("Then verify the \"Alıcı Hesabı\" field is masked");
        // Said out loud: this line is not the model's.
        assertThat(out).contains("# dogrulama orchestrator tarafindan eklendi");
        // Appended after the last step, at that step's indentation.
        assertThat(out.strip()).endsWith("Then verify the \"Alıcı Hesabı\" field is masked");
        assertThat(out).contains("    Then verify");
    }

    @Test
    @DisplayName("mixed Turkish and ASCII in the request still finds the label")
    void foldsBothAlphabets() {
        // "alici hesabinin" typed flat must still reach "Alıcı Hesabı".
        String out = ReadOnlyAssertion.apply(NO_ASSERTION, detailScreen(), serving(),
                "alici hesabinin yildizli oldugunu dogrula");

        assertThat(out).contains("verify the \"Alıcı Hesabı\" field is masked");
    }

    @Test
    @DisplayName("the longest matching label wins")
    void longestLabelWins() {
        // "Durum" is a substring of half the sentences on these screens.
        String out = ReadOnlyAssertion.apply(NO_ASSERTION, detailScreen(), serving(),
                "durum kodu maskeli mi kontrol et");

        assertThat(out).contains("verify the \"Durum Kodu\" field is masked");
        assertThat(out).doesNotContain("verify the \"Durum\" field is masked");
    }

    @Test
    @DisplayName("stays out when the draft already asserts something")
    void doesNotSecondGuessAnExistingAssertion() {
        String withCheck = NO_ASSERTION + "    Then verify \"Bir sey\" text is displayed\n";

        assertThat(ReadOnlyAssertion.apply(withCheck, detailScreen(), serving(), ASKED))
                .isEqualTo(withCheck);
    }

    @Test
    @DisplayName("stays out when the screen is not read-only")
    void onlyForScreensNobodyTypesInto() {
        // A form: the model could have earned an assertion by filling it in,
        // and if it did not, that is the validator's finding to report.
        CatalogModel form = CatalogModel.parse("""
                app: NGC
                flow: Fatura Olustur
                fields:
                  - label: "Alıcı Hesabı"
                    type: text
                """).orElseThrow();

        assertThat(ReadOnlyAssertion.apply(NO_ASSERTION, form, serving(), ASKED))
                .isEqualTo(NO_ASSERTION);
    }

    @Test
    @DisplayName("stays out when this executor does not publish the step")
    void neverWritesAStepTheDeploymentDoesNotServe() {
        // The DateVerb lesson: a rewrite that outruns the deployment comes back
        // "Tanımsız adım" and suppresses every other finding with it.
        Vocabulary behind = vocabulary(NAVIGATION + "|verify {string} text is displayed");

        assertThat(ReadOnlyAssertion.apply(NO_ASSERTION, detailScreen(), behind, ASKED))
                .isEqualTo(NO_ASSERTION);
    }

    @Test
    @DisplayName("stays out when the request does not ask about masking")
    void onlyWhenMaskingWasAskedFor() {
        assertThat(ReadOnlyAssertion.apply(NO_ASSERTION, detailScreen(), serving(),
                "alıcı hesabını kontrol et"))
                .isEqualTo(NO_ASSERTION);
    }

    @Test
    @DisplayName("stays out when no catalogued label is named in the request")
    void declinesRatherThanGuessTheField() {
        // A near miss must produce nothing. Writing an assertion about the
        // wrong field is worse than the complaint it would have replaced.
        assertThat(ReadOnlyAssertion.apply(NO_ASSERTION, detailScreen(), serving(),
                "ekrandaki bilgiler yıldızlı mı bak"))
                .isEqualTo(NO_ASSERTION);
    }

    @Test
    @DisplayName("a null catalog or vocabulary changes nothing")
    void nullsAreNoOps() {
        assertThat(ReadOnlyAssertion.apply(NO_ASSERTION, null, serving(), ASKED))
                .isEqualTo(NO_ASSERTION);
        assertThat(ReadOnlyAssertion.apply(NO_ASSERTION, detailScreen(), null, ASKED))
                .isEqualTo(NO_ASSERTION);
        assertThat(ReadOnlyAssertion.apply(NO_ASSERTION, detailScreen(), serving(), null))
                .isEqualTo(NO_ASSERTION);
    }

    /* ----------------------------------------------------------------- */

    private static CatalogModel detailScreen() {
        return CatalogModel.parse("""
                app: NGC
                flow: Odeme Detay
                fields:
                  - label: "Alıcı Hesabı"
                    type: text
                    readonly: true
                    verifiable: true
                  - label: "Durum"
                    type: text
                    readonly: true
                    verifiable: true
                  - label: "Durum Kodu"
                    type: text
                    readonly: true
                    verifiable: true
                """).orElseThrow();
    }

    private static final String NAVIGATION =
            "login with cif {string} and userid {string}"
                    + "|navigate to {string} {string}"
                    + "|click {string}"
                    + "|select row {string}";

    /** An executor that publishes the mask step. */
    private static Vocabulary serving() {
        return vocabulary(NAVIGATION + "|verify the {string} field is masked");
    }

    private static Vocabulary vocabulary(String patterns) {
        return new Vocabulary(List.of(patterns.split("\\|")).stream()
                .map(ReadOnlyAssertionTest::step)
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
