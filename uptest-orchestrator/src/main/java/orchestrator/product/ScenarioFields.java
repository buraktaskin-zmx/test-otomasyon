package orchestrator.product;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One scenario as the database keeps it: the natural-language half and the
 * Gherkin half in separate fields.
 *
 * They used to share one .feature file -- the prose as the Scenario's
 * description -- and editing one half could quietly damage the other: a
 * whole file pasted into the Gherkin box doubled the rule tags, and a
 * description that lost its section headers came back as "yok". Kept apart,
 * a Gherkin edit touches {@link #gherkin} and nothing else.
 *
 * {@link FeatureComposer} rebuilds the file a run or an export needs;
 * {@link ScenarioDocParser} goes the other way, for the import and for
 * clients that still send a whole file.
 *
 * @param title         the Scenario's name -- also the {@code Scenario:} line
 *                      inside {@link #gherkin}, kept in step by the store
 * @param rules         {@code R-AL-014}, without the '@'
 * @param needs         {@code FIRMA_TEST}, or {@code none} said out loud
 * @param otherTags     every other tag ({@code mutating}), without the '@'
 * @param gherkin       {@code Feature:} + {@code Scenario:} + steps; no tag line
 *                      above Feature and no description under Scenario
 */
public record ScenarioFields(String title, List<String> rules, List<String> needs,
                             List<String> otherTags, List<String> preconditions,
                             List<String> steps, List<String> expected, String gherkin) {

    public ScenarioFields {
        title = title == null ? "" : title.trim();
        rules = clean(rules);
        needs = clean(needs);
        otherTags = clean(otherTags);
        preconditions = clean(preconditions);
        steps = clean(steps);
        expected = clean(expected);
        // No comment lines in any Gherkin, by the analysts' rule.
        gherkin = gherkin == null ? "" : COMMENT_LINE.matcher(gherkin).replaceAll("");
    }

    private static final Pattern COMMENT_LINE =
            Pattern.compile("(?m)^[ \\t]*#.*(?:\\R|$)");

    /** Trimmed, blank items dropped, a leading "- " a person typed removed. */
    private static List<String> clean(List<String> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item != null)
                .map(item -> item.trim().replaceFirst("^[-•*]\\s+", "").trim())
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
