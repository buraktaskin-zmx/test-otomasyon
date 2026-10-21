package orchestrator.product;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A whole .feature file split into {@link ScenarioFields}.
 *
 * The reading rules are the UI's own ({@code frontend/src/lib/scenarioProse.ts},
 * {@code readDoc}) moved to Java, so a file imported here comes out exactly as
 * the detail view used to show it:
 *
 * <pre>
 *   @R-AL-014 @needs:none          tags above Feature  -> rules / needs / otherTags
 *   Feature: ...                   ┐
 *     Scenario: title              ├ gherkin, minus the description below
 *       Ön koşul: / Adımlar: /     ┘ the description  -> three lists
 *       Beklenen:  with "- " items
 *       Given ...                    gherkin
 * </pre>
 *
 * Older files carry free prose instead of sections; an "Ön koşul:" or
 * "Beklenen:" paragraph is still recognised, and the rest is split into steps
 * by sentence, exactly as readDoc does.
 */
public final class ScenarioDocParser {

    static final Pattern SCENARIO = Pattern.compile("^(\\s*)Scenario( Outline)?:\\s*(.*)$");

    /** A description ends where Gherkin structure starts again. */
    private static final Pattern STRUCTURE =
            Pattern.compile("^\\s*(Given|When|Then|And|But|\\*|Examples:|Scenario|Background:|@)");

    private static final Pattern PRECONDITIONS =
            Pattern.compile("^(ön|on)\\s*ko[sş]ul(lar)?\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STEPS =
            Pattern.compile("^ad[ıi]mlar\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXPECTED =
            Pattern.compile("^beklenen(ler)?\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final String NEEDS_PREFIX = "@needs:";

    private ScenarioDocParser() {
    }

    public static ScenarioFields parse(String feature) {
        List<String> lines = new ArrayList<>(List.of((feature == null ? "" : feature).split("\\R", -1)));

        // Tag lines above Feature: blank or '@' lines at the very top.
        int first = 0;
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        while (first < lines.size() && (lines.get(first).isBlank() || lines.get(first).trim().startsWith("@"))) {
            for (String token : lines.get(first).trim().split("\\s+")) {
                if (token.startsWith("@") && token.length() > 1) {
                    tags.add(token);
                }
            }
            first++;
        }
        List<String> body = new ArrayList<>(lines.subList(first, lines.size()));

        List<String> rules = new ArrayList<>();
        List<String> needs = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String tag : tags) {
            if (tag.regionMatches(true, 0, "@R-", 0, 3)) {
                rules.add(tag.substring(1));
            } else if (tag.toLowerCase(Locale.ROOT).startsWith(NEEDS_PREFIX)) {
                needs.add(tag.substring(NEEDS_PREFIX.length()));
            } else {
                other.add(tag.substring(1));
            }
        }

        int head = -1;
        for (int i = 0; i < body.size(); i++) {
            if (SCENARIO.matcher(body.get(i)).matches()) {
                head = i;
                break;
            }
        }
        if (head < 0) {
            return new ScenarioFields("", rules, needs, other, List.of(), List.of(), List.of(),
                    String.join("\n", body).strip() + "\n");
        }
        Matcher match = SCENARIO.matcher(body.get(head));
        match.matches();
        String title = match.group(3).trim();

        int end = head + 1;
        while (end < body.size() && !STRUCTURE.matcher(body.get(end)).find()) {
            end++;
        }

        Sections sections = read(body.subList(head + 1, end));

        // The Gherkin half: everything except the description, with one blank
        // line between the Scenario line and its first step, as the files
        // were written.
        List<String> gherkin = new ArrayList<>(body.subList(0, head + 1));
        if (end < body.size()) {
            gherkin.add("");
            gherkin.addAll(body.subList(end, body.size()));
        }
        String text = String.join("\n", gherkin).strip() + "\n";

        return new ScenarioFields(title, rules, needs, other,
                sections.preconditions, sections.steps, sections.expected, text);
    }

    private record Sections(List<String> preconditions, List<String> steps, List<String> expected) {
    }

    /** readDoc's loop, line for line. */
    private static Sections read(List<String> description) {
        List<String> preconditions = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        List<String> section = null;
        List<String> loose = new ArrayList<>();
        // Whether the current section's last item may still grow by a wrapped line.
        boolean open = false;

        for (String raw : description) {
            String line = raw.trim();
            if (line.isEmpty()) {
                open = false;
                continue;
            }
            // A comment is not prose; it must not become a step.
            if (line.startsWith("#")) {
                continue;
            }
            Matcher header;
            List<String> target = null;
            String rest = null;
            if ((header = PRECONDITIONS.matcher(line)).matches()) {
                target = preconditions;
                rest = header.group(3);
            } else if ((header = STEPS.matcher(line)).matches()) {
                target = steps;
                rest = header.group(1);
            } else if ((header = EXPECTED.matcher(line)).matches()) {
                target = expected;
                rest = header.group(2);
            }
            if (target != null) {
                section = target;
                rest = rest == null ? "" : rest.trim();
                if (!rest.isEmpty()) {
                    section.add(rest);
                    open = true;
                } else {
                    open = false;
                }
                continue;
            }
            if (line.startsWith("- ") || line.equals("-")) {
                String item = line.substring(1).trim();
                if (section != null) {
                    section.add(item);
                    open = true;
                } else {
                    loose.add(item);
                }
                continue;
            }
            if (section != null && open) {
                String last = section.isEmpty() ? "" : section.get(section.size() - 1);
                // "…şu değerler görünür:" announces a list written as plain lines.
                if (last.endsWith(":")) {
                    section.add(line);
                } else if (section.isEmpty()) {
                    section.add(line);
                } else {
                    section.set(section.size() - 1, (last + " " + line).trim());
                }
                continue;
            }
            // Prose outside any section: legacy free text, read as steps.
            section = null;
            loose.add(line);
        }

        if (!loose.isEmpty()) {
            List<String> all = new ArrayList<>(sentences(String.join(" ", loose)));
            all.addAll(steps);
            steps = all;
        }
        return new Sections(preconditions, steps, expected);
    }

    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("(?<=\\.)\\s+")) {
            String sentence = part.trim().replaceAll("\\.$", "").trim();
            if (!sentence.isEmpty()) {
                out.add(sentence);
            }
        }
        return out;
    }
}
