package orchestrator.product;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * {@link ScenarioFields} back into one .feature file -- what a run, the
 * registry's Gherkin parse and an export need.
 *
 * The layout is the one the analysts' files already have (and the UI's
 * {@code writeDoc} wrote): the tag line, then Feature, then the Scenario line
 * followed by its "Ön koşul: / Adımlar: / Beklenen:" sections, then the
 * steps. No comment lines, ever -- the analysts' rule for every Gherkin file.
 */
public final class FeatureComposer {

    private FeatureComposer() {
    }

    public static String compose(ScenarioFields fields) {
        List<String> tags = new ArrayList<>();
        fields.rules().forEach(rule -> tags.add("@" + rule.replace("@", "").replaceAll("\\s+", "")));
        fields.needs().forEach(need -> tags.add("@needs:" + need.replaceAll("\\s+", "")));
        fields.otherTags().forEach(tag -> tags.add("@" + tag.replace("@", "").replaceAll("\\s+", "")));

        List<String> lines = new ArrayList<>(List.of(
                renamed(fields.gherkin(), fields.title()).strip().split("\\R", -1)));

        int head = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (ScenarioDocParser.SCENARIO.matcher(lines.get(i)).matches()) {
                head = i;
                break;
            }
        }
        if (head >= 0) {
            Matcher match = ScenarioDocParser.SCENARIO.matcher(lines.get(head));
            match.matches();
            String pad = match.group(1) + "  ";

            List<String> description = new ArrayList<>();
            section(description, pad, "Ön koşul:", fields.preconditions());
            section(description, pad, "Adımlar:", fields.steps());
            section(description, pad, "Beklenen:", fields.expected());

            int next = head + 1;
            while (next < lines.size() && lines.get(next).isBlank()) {
                next++;
            }
            List<String> out = new ArrayList<>(lines.subList(0, head + 1));
            out.addAll(description);
            if (next < lines.size()) {
                out.add("");
                out.addAll(lines.subList(next, lines.size()));
            }
            lines = out;
        }

        String body = String.join("\n", lines).strip() + "\n";
        return tags.isEmpty() ? body : String.join(" ", tags) + "\n" + body;
    }

    /**
     * The gherkin with its {@code Scenario:} line carrying {@code title}. The
     * title is edited on the natural-language side and the line on the
     * Gherkin side; this keeps the two from disagreeing.
     */
    public static String renamed(String gherkin, String title) {
        if (gherkin == null || title == null || title.isBlank()) {
            return gherkin == null ? "" : gherkin;
        }
        String[] lines = gherkin.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher match = ScenarioDocParser.SCENARIO.matcher(lines[i]);
            if (match.matches()) {
                lines[i] = match.group(1) + "Scenario" + (match.group(2) == null ? "" : match.group(2))
                        + ": " + title.trim();
                break;
            }
        }
        return String.join("\n", lines);
    }

    /** The {@code Scenario:} line's name, or "" when there is none. */
    public static String titleOf(String gherkin) {
        for (String line : (gherkin == null ? "" : gherkin).split("\\R")) {
            Matcher match = ScenarioDocParser.SCENARIO.matcher(line);
            if (match.matches()) {
                return match.group(3).trim();
            }
        }
        return "";
    }

    private static void section(List<String> out, String pad, String label, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        out.add("");
        out.add(pad + label);
        items.forEach(item -> out.add(pad + "- " + item));
    }
}
