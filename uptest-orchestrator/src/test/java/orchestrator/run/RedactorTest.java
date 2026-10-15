package orchestrator.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test that must never be allowed to go red.
 *
 * Redactor is the only thing standing between live bank customer credentials
 * and a database with no retention policy. LoginStep.forApp writes a real cif
 * and userid into the Gherkin, and three columns were about to store that text
 * verbatim: TEST_RUN.feature, TEST_RUN.last_status_json (which carries
 * steps[].text) and TEST_RUN_STEP.step_text.
 *
 * NOTE THE ASSERTION STYLE. Every credential test asserts that the credential
 * string is ABSENT FROM THE WHOLE OUTPUT, never that the output equals some
 * expected line. The first form catches a future code path that copies the
 * login line somewhere else in the file; the second does not.
 */
class RedactorTest {

    /** Shaped like the real thing — the values from the UI's own sample. */
    private static final String CIF = "DI00002018";
    private static final String USERID = "3014";

    private static final String NGC_FEATURE = String.join("\n",
            "Feature: Banko Ödeme Talebi",
            "",
            "  Scenario: Kullanıcı Banko ekranından ödeme talep eder",
            "    Given login with cif \"" + CIF + "\" and userid \"" + USERID + "\"",
            "    And navigate to \"NAKİT YÖNETİMİ\" \"Ödeme Oluştur / Talep Et\"",
            "    And enter amount \"Ödeme Tutarı\" \"1250,55\"",
            "    Then verify the \"Ödeme Tutarı\" has amount \"1250,55\"",
            "");

    private static final String NGI_FEATURE = String.join("\n",
            "Feature: NGI Login",
            "  Scenario: User has account and can login",
            "    Given login to ngi with cif \"" + CIF + "\"",
            "    And click \"Devam\"",
            "");

    // ---------------------------------------------------------------- feature

    @Test
    @DisplayName("NGC: neither the cif nor the userid survives anywhere in the output")
    void ngcCredentialsAreGone() {
        String redacted = Redactor.redactFeature(NGC_FEATURE);

        assertThat(redacted)
                .doesNotContain(CIF)
                .doesNotContain(USERID);
    }

    @Test
    @DisplayName("NGI: the customer code does not survive either")
    void ngiCredentialsAreGone() {
        assertThat(Redactor.redactFeature(NGI_FEATURE)).doesNotContain(CIF);
    }

    @Test
    @DisplayName("the login step is still recognisably a login step")
    void loginStepStaysReadable() {
        assertThat(Redactor.redactFeature(NGC_FEATURE))
                .contains("Given login with cif \"***\" and userid \"***\"");
        assertThat(Redactor.redactFeature(NGI_FEATURE))
                .contains("Given login to ngi with cif \"***\"");
    }

    /**
     * The scenario has to stay the scenario. Redacting an amount or a menu
     * name would leave a TEST_RUN row that describes nothing an analyst could
     * recognise, which defeats the purpose of storing the feature at all.
     */
    @Test
    @DisplayName("a non-login step's quoted values survive byte for byte")
    void nonLoginArgumentsAreUntouched() {
        String redacted = Redactor.redactFeature(NGC_FEATURE);

        assertThat(redacted)
                .contains("And navigate to \"NAKİT YÖNETİMİ\" \"Ödeme Oluştur / Talep Et\"")
                .contains("And enter amount \"Ödeme Tutarı\" \"1250,55\"")
                .contains("Then verify the \"Ödeme Tutarı\" has amount \"1250,55\"");
    }

    @Test
    @DisplayName("line count and every non-login line are preserved exactly")
    void onlyTheLoginLineChanges() {
        String[] before = NGC_FEATURE.split("\\R", -1);
        String[] after = Redactor.redactFeature(NGC_FEATURE).split("\\R", -1);

        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.length; i++) {
            if (before[i].contains("login")) {
                assertThat(after[i]).isNotEqualTo(before[i]);
            } else {
                assertThat(after[i]).as("line %d", i + 1).isEqualTo(before[i]);
            }
        }
    }

    @ParameterizedTest(name = "keyword {0} is redacted")
    @ValueSource(strings = {"Given", "When", "Then", "And", "But", "*"})
    @DisplayName("every Gherkin keyword is covered, not just the one LoginStep writes")
    void everyKeywordIsRedacted(String keyword) {
        String feature = "  Scenario: x\n    " + keyword
                + " login with cif \"" + CIF + "\" and userid \"" + USERID + "\"\n";

        assertThat(Redactor.redactFeature(feature))
                .doesNotContain(CIF)
                .doesNotContain(USERID)
                .contains(keyword + " login with cif \"***\" and userid \"***\"");
    }

    /**
     * "login failed" is this application's own error message and a legitimate
     * thing to assert on. A rule that redacted any line mentioning login would
     * blank the only meaningful argument of that assertion.
     */
    @Test
    @DisplayName("a step that merely mentions login mid-sentence is not redacted")
    void loginMidSentenceIsNotAStepToRedact() {
        String feature = "  Scenario: x\n    And verify \"login failed\" text is displayed\n";

        assertThat(Redactor.redactFeature(feature)).isEqualTo(feature);
    }

    @Test
    @DisplayName("the word boundary holds: logintest is not login")
    void wordBoundaryIsRespected() {
        String feature = "  Scenario: x\n    And logintest \"keep-me\"\n";

        assertThat(Redactor.redactFeature(feature)).isEqualTo(feature);
    }

    /**
     * The mask is itself a quoted argument, so a second pass finds "***" and
     * replaces it with "***". This matters because redactFeature runs at the
     * POST /runs boundary and a re-submitted feature may already be redacted.
     */
    @Test
    @DisplayName("redaction is idempotent")
    void redactionIsIdempotent() {
        String once = Redactor.redactFeature(NGC_FEATURE);

        assertThat(Redactor.redactFeature(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("a value containing $ or a backslash does not blow up the replacement")
    void regexReplacementMetacharactersAreLiteral() {
        String feature = "  Scenario: x\n    Given login with cif \"a$1\\b\" and userid \"c\"\n";

        assertThat(Redactor.redactFeature(feature))
                .contains("Given login with cif \"***\" and userid \"***\"")
                .doesNotContain("a$1");
    }

    // ------------------------------------------------------------- step text

    /**
     * The hole the original design missed. StepState carries keyword and text
     * in SEPARATE fields, so the executor reports the login step's text with
     * no keyword at all — and that text travels into last_status_json and into
     * TEST_RUN_STEP.step_text.
     */
    @Test
    @DisplayName("step text: a keyword-less login step is redacted")
    void stepTextWithoutKeywordIsRedacted() {
        String text = "login with cif \"" + CIF + "\" and userid \"" + USERID + "\"";

        assertThat(Redactor.redactStepText(text))
                .doesNotContain(CIF)
                .doesNotContain(USERID)
                .isEqualTo("login with cif \"***\" and userid \"***\"");
    }

    @Test
    @DisplayName("step text: NGI's keyword-less login step is redacted")
    void ngiStepTextIsRedacted() {
        assertThat(Redactor.redactStepText("login to ngi with cif \"" + CIF + "\""))
                .doesNotContain(CIF)
                .isEqualTo("login to ngi with cif \"***\"");
    }

    @Test
    @DisplayName("step text: a non-login step is returned unchanged")
    void nonLoginStepTextIsUntouched() {
        String text = "enter amount \"Ödeme Tutarı\" \"1250,55\"";

        assertThat(Redactor.redactStepText(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("step text: must BEGIN with login, not merely contain it")
    void stepTextMustBeginWithLogin() {
        String text = "verify \"login failed\" text is displayed";

        assertThat(Redactor.redactStepText(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("step text: redaction is idempotent")
    void stepTextRedactionIsIdempotent() {
        String once = Redactor.redactStepText("login with cif \"" + CIF + "\" and userid \"x\"");

        assertThat(Redactor.redactStepText(once)).isEqualTo(once);
    }

    // ------------------------------------------------------------ null / blank

    @Test
    @DisplayName("null and empty input are handled without a branch at the call site")
    void nullAndEmptyAreSafe() {
        assertThat(Redactor.redactFeature(null)).isNull();
        assertThat(Redactor.redactFeature("")).isEmpty();
        assertThat(Redactor.redactStepText(null)).isNull();
        assertThat(Redactor.redactStepText("")).isEmpty();
    }

    @Test
    @DisplayName("whitespace-only input is returned unchanged, not mangled")
    void blankIsSafe() {
        assertThat(Redactor.redactFeature("   \n  \n")).isEqualTo("   \n  \n");
        assertThat(Redactor.redactStepText("   ")).isEqualTo("   ");
    }
}
