package orchestrator.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the scenario's login line instead of asking the model for it.
 *
 * NGC and NGI are different applications with different login steps — cif AND
 * userid for the corporate app, a customer code alone for the individual one —
 * and the catalog's "app" key already says which. Leaving the choice to the
 * model made it a coin flip: given an intent that named a cif but no userid,
 * the same request produced the NGC step with an invented userid on one run and
 * the NGI step, logging into the wrong application entirely, on the next.
 *
 * So the orchestrator decides. Everything needed is already known before the
 * model is called, and a fact that is known should never be a question.
 */
public final class LoginStep {

    /**
     * Any login line the model wrote, whichever step and keyword it used. The
     * draft is rewritten rather than trusted: a prompt rule asks, this replaces.
     */
    private static final Pattern EXISTING_LOGIN =
            Pattern.compile("(?m)^[ \\t]*(?:Given|When|And|But)[ \\t]+login\\b.*(?:\\R|$)");

    /** Where the login belongs: straight after the scenario's own title line. */
    private static final Pattern SCENARIO_TITLE =
            Pattern.compile("(?m)^([ \\t]*)(Scenario|Scenario Outline|Senaryo)\\s*:.*(?:\\R|$)");

    private LoginStep() {
    }

    /**
     * @return the Gherkin line, or null when no credentials were supplied and
     *         the model should keep writing its own
     */
    public static String forApp(String app, String cif, String userid) {
        if (isBlank(cif)) {
            return null;
        }
        if ("NGI".equalsIgnoreCase(app)) {
            return "Given login to ngi with cif \"" + cif.trim() + "\"";
        }
        return "Given login with cif \"" + cif.trim() + "\" and userid \"" + userid.trim() + "\"";
    }

    /** NGC needs both halves; NGI's password lives in its page object, not here. */
    public static boolean needsUserid(String app) {
        return !"NGI".equalsIgnoreCase(app);
    }

    /**
     * Drops whatever login the draft contains and puts the given one under the
     * scenario title.
     *
     * A draft with no scenario title is returned untouched: it is malformed in
     * a way the validator reports far better than a guess at where to graft a
     * line would.
     */
    public static String apply(String feature, String loginStep) {
        if (loginStep == null || feature == null) {
            return feature;
        }

        String withoutLogin = EXISTING_LOGIN.matcher(feature).replaceAll("");
        Matcher title = SCENARIO_TITLE.matcher(withoutLogin);
        if (!title.find()) {
            return feature;
        }

        // Indent the login like the steps around it, not like the title.
        String indent = title.group(1) + "  ";

        // Before the FIRST STEP, not straight under the title: a registered
        // scenario carries its natural-language description (Ön koşul /
        // Adımlar / Beklenen) between the two, and Gherkin refuses free text
        // after a step -- grafting the login above it made every such file
        // unparseable the moment it was run. With no description the first
        // step IS the next line, so generated drafts land exactly as before.
        Matcher step = FIRST_STEP.matcher(withoutLogin);
        int at = step.find(title.end()) ? step.start() : title.end();
        return withoutLogin.substring(0, at)
                + indent + loginStep + "\n"
                + withoutLogin.substring(at);
    }

    private static final Pattern FIRST_STEP =
            Pattern.compile("(?m)^[ \\t]*(?:Given|When|Then|And|But|\\*)[ \\t]");

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
