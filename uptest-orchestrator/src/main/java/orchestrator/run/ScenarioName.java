package orchestrator.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scenario's own title, so the history list reads like the analyst's work
 * rather than a column of UUIDs.
 *
 * Lifted out of RunService for two reasons, both of which arrive with the
 * TEST_RUN table:
 *
 *  1. The title is now DERIVED ONCE, at insert, and stored in a column. It
 *     cannot be re-derived later, because the text it is parsed from is
 *     redacted on the way in (see {@link Redactor}) — and because re-deriving
 *     it on every read would make the history listing's output depend on a
 *     parser that may change.
 *  2. It is the one piece of RunService's behaviour that is pure and testable
 *     without a Spring context, and it is a UI contract: app.js renders this
 *     straight into the history table's "Senaryo" column.
 */
public final class ScenarioName {

    /** What describe() has always emitted when nothing could be parsed. */
    public static final String UNNAMED = "(isimsiz)";

    /**
     * Matches TEST_RUN.scenario_name's column width.
     *
     * Truncation happens HERE, in Java, never in the column: H2 raises
     * "Value too long for column" rather than truncating, which would turn a
     * verbose scenario title into a 500 on the one run that used it.
     */
    public static final int MAX_LENGTH = 512;

    /**
     * Outline keywords first in the alternation so "Scenario Outline: x" is
     * read as an outline rather than sent down a backtracking path. The pair
     * of Turkish spellings is the same one ScenarioValidator.OUTLINE knows
     * about, and keeping the two in step is the point of writing them the
     * same way.
     *
     * `\s*:` rather than a literal "Scenario:" so "Scenario : x" parses too —
     * Gherkin accepts it, the validator's own pattern accepts it, and a title
     * that reaches the parser but not this method would land in the history as
     * "(isimsiz)" for a reason nobody could see.
     */
    private static final Pattern SCENARIO_TITLE = Pattern.compile(
            "^(?:Scenario Outline|Senaryo Taslağı|Scenario|Senaryo)\\s*:(.*)$");

    private static final Pattern FEATURE_TITLE = Pattern.compile(
            "^(?:Feature|Özellik)\\s*:(.*)$");

    private ScenarioName() {
    }

    /**
     * The scenario title, falling back to the feature title, then to a
     * placeholder.
     *
     * The scenario wins even when a Feature line came first, which is the
     * normal shape of every file in scenarios/ — the feature title names the
     * screen, the scenario title names what was tested, and the second is what
     * an analyst scanning the history is looking for.
     */
    public static String of(String feature) {
        if (feature == null) {
            return UNNAMED;
        }

        String featureTitle = null;
        for (String raw : feature.split("\\R")) {
            String line = raw.trim();

            Matcher scenario = SCENARIO_TITLE.matcher(line);
            if (scenario.matches()) {
                return clamp(scenario.group(1).trim());
            }

            if (featureTitle == null) {
                Matcher title = FEATURE_TITLE.matcher(line);
                if (title.matches()) {
                    featureTitle = title.group(1).trim();
                }
            }
        }

        return featureTitle != null && !featureTitle.isEmpty()
                ? clamp(featureTitle)
                : UNNAMED;
    }

    /**
     * An empty title ("Scenario:" with nothing after it) becomes the
     * placeholder rather than an empty string: scenario_name is NOT NULL and a
     * blank cell in the history table reads as a rendering bug.
     */
    private static String clamp(String name) {
        if (name.isEmpty()) {
            return UNNAMED;
        }
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        // The ellipsis replaces a character rather than being appended, so the
        // result is MAX_LENGTH and not MAX_LENGTH + 1.
        return name.substring(0, MAX_LENGTH - 1) + "…";
    }
}
