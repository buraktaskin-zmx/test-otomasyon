package orchestrator.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips credentials out of Gherkin before anything writes it down.
 *
 * WHY THIS EXISTS. LoginStep.forApp builds the login line out of the request's
 * cif and userid:
 *
 *     Given login with cif "12345678" and userid "AB1234"      (NGC)
 *     Given login to ngi with cif "12345678"                   (NGI)
 *
 * and LoginStep.apply grafts it into every draft, on every repair turn. So the
 * feature text — the thing the analyst edits, the thing POST /api/v1/runs
 * receives, the thing a TEST_RUN row was about to store verbatim and forever —
 * carries live bank customer credentials in plaintext. "We never persist the
 * cif request field" is true and completely beside the point: the credentials
 * come in through the back door, inside the scenario.
 *
 * THREE PLACES, NOT ONE. The credential is not only in the feature text:
 *
 *   TEST_RUN.feature              the Gherkin the analyst submitted
 *   TEST_RUN.last_status_json     the executor's payload carries steps[].text,
 *                                 and a step's text IS the line with its
 *                                 arguments
 *   TEST_RUN_STEP.step_text       the same text, parsed into a row
 *
 * Redacting only the first would have looked complete and leaked through the
 * other two. Hence two entry points: {@link #redactFeature(String)} for whole
 * Gherkin (keyword-prefixed lines) and {@link #redactStepText(String)} for a
 * StepState text, where the keyword is a SEPARATE field and the text therefore
 * begins with "login" directly.
 *
 * GENERIC OVER HARDCODED. This class does not know about NGC, NGI, cif or
 * userid. It blanks EVERY quoted argument of ANY step whose text begins with
 * "login", so a third application with a fourth login step is covered the day
 * it is written and nobody has to remember this file exists.
 *
 * NON-LOGIN STEPS ARE UNTOUCHED. An amount, an account label and a menu name
 * are the scenario; redacting them would leave a row that describes nothing.
 *
 * A redacted feature cannot be re-run. That is correct, not a limitation:
 * re-running an old scenario against a bank should require re-entering who you
 * are logging in as.
 */
public final class Redactor {

    /** What a credential becomes. Itself a quoted argument, which is what
     *  makes both methods idempotent: redacting "***" yields "***". */
    static final String MASK = "\"***\"";

    /**
     * A login step inside a feature file: a Gherkin keyword, then "login".
     *
     * `Then` and `*` are included where LoginStep's own pattern omits them.
     * LoginStep is WRITING the line and so controls its keyword; this is
     * READING whatever an analyst left in the editor, and Gherkin accepts both.
     * Over-matching here costs nothing — the step is still a login step.
     *
     * (?m) with `.*$` stays on one line: `.` does not match a line terminator.
     */
    private static final Pattern LOGIN_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:Given|When|Then|And|But|\\*)[ \\t]+login\\b.*$");

    /**
     * A login step as the executor reports it. StepState carries `keyword` and
     * `text` in separate fields, so the text arrives as
     * `login with cif "…" and userid "…"` with no keyword at all.
     */
    private static final Pattern LOGIN_TEXT = Pattern.compile("^[ \\t]*login\\b");

    /** A Cucumber string argument. Non-greedy by construction: the character
     *  class excludes the closing quote, so it cannot span two arguments. */
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"");

    private Redactor() {
    }

    /**
     * Whole Gherkin in, whole Gherkin out, with every login step's quoted
     * arguments masked.
     *
     * Line count, indentation, line endings and every non-login line are
     * preserved byte for byte — the stored feature has to stay readable as the
     * scenario it was, otherwise the TEST_RUN row records nothing an analyst
     * can recognise.
     */
    public static String redactFeature(String gherkin) {
        if (gherkin == null || gherkin.isEmpty()) {
            return gherkin;
        }

        Matcher line = LOGIN_LINE.matcher(gherkin);
        StringBuilder out = new StringBuilder(gherkin.length());
        while (line.find()) {
            // quoteReplacement because the matched line is literal text: a
            // backslash or a $ in a scenario value would otherwise be read as
            // a replacement reference and throw.
            line.appendReplacement(out, Matcher.quoteReplacement(blankQuoted(line.group())));
        }
        line.appendTail(out);
        return out.toString();
    }

    /**
     * One StepState.text in, the same text out, masked only when it IS a login
     * step.
     *
     * lookingAt() rather than find(): the step must BEGIN with "login".
     * Otherwise `verify "login failed" text is displayed` — a legitimate
     * assertion on this application's own error message — would have its only
     * meaningful argument blanked.
     */
    public static String redactStepText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return LOGIN_TEXT.matcher(text).lookingAt() ? blankQuoted(text) : text;
    }

    private static String blankQuoted(String stepLine) {
        return QUOTED.matcher(stepLine).replaceAll(Matcher.quoteReplacement(MASK));
    }
}
