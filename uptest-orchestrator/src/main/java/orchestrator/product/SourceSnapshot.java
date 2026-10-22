package orchestrator.product;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a scenario's rules and aliases SAID when somebody last read them.
 *
 * <p>A scenario is bound to a rule by id alone, and that binding is re-made
 * on every request by comparing strings. So a rule being renamed or deleted
 * is caught ({@code known=false}), and a rule whose TEXT changed is not: the
 * id still matches, the chip still looks bound, and five scenarios quietly
 * go on testing the old wording. This class is the missing half.
 *
 * <p><b>A full copy, not a hash.</b> A hash answers "did it change", which is
 * the cheap half of the question; the expensive half is "what changed", and
 * that is the only one an analyst can act on. The stored copy is what the
 * diff is rendered from. Cost is about a kilobyte per bound scenario.
 *
 * <p><b>Deliberately NOT a judgement.</b> This reports a difference and stops
 * there, because the same difference means opposite things: if the
 * application changed and the rule caught up, the scenario should follow; if
 * the rule was simply written down wrong and has been corrected, the scenario
 * should change AND the run should go red, because the application still has
 * the bug. Rewriting the scenario automatically would hide exactly that
 * second case — a passing test over a live defect. So the machine says what
 * moved and a person decides which of the two it was.
 *
 * <p>Plain data in, plain data out: no Spring, no JPA, no clock. The store
 * serialises {@link #capture} and hands it back to {@link #compare}.
 */
public final class SourceSnapshot {

    /** A rule or an alias, as it stood. Null-free so comparison is total. */
    public record Source(String kind, String id, Map<String, String> fields) {

        public static final String RULE = "RULE";
        public static final String ALIAS = "ALIAS";

        public Source {
            kind = kind == null ? "" : kind;
            id = id == null ? "" : id;
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }

        public String field(String name) {
            return fields.getOrDefault(name, "");
        }
    }

    /** One field that reads differently now than it did at review time. */
    public record FieldChange(String field, String before, String after) {
    }

    /**
     * What happened to one of a scenario's sources since it was reviewed.
     *
     * @param state   {@link #CHANGED} or {@link #GONE}. A source the scenario
     *                has gained since the stamp is NOT reported: a rule the
     *                analyst has just tagged was read as they tagged it
     * @param removed values the rule listed then and does not list now, the
     *                ones worth hunting for in the scenario's own text
     */
    public record SourceChange(String kind, String id, String state, String title,
                               List<FieldChange> fields,
                               List<String> removed, List<String> added) {

        public static final String CHANGED = "CHANGED";
        public static final String GONE = "GONE";
    }

    private static final String VALUE_PREFIX = "deger:";

    private SourceSnapshot() {
    }

    /**
     * Every rule and alias this scenario names, as the product states them
     * right now. An id the product does not know is skipped: there is nothing
     * to snapshot, and {@code known=false} already reports it.
     */
    public static List<Source> capture(ScenarioFields fields, ProductModel product) {
        List<Source> sources = new ArrayList<>();
        if (fields == null || product == null) {
            return sources;
        }
        for (String id : fields.rules()) {
            product.rules().stream()
                    .filter(rule -> rule.id().equals(id))
                    .findFirst()
                    .ifPresent(rule -> sources.add(of(rule)));
        }
        for (String id : fields.needs()) {
            DataAlias alias = product.alias(id);
            if (alias != null) {
                sources.add(of(alias));
            }
        }
        return sources;
    }

    /** A rule flattened to named strings, so one comparison covers every field. */
    public static Source of(Rule rule) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", rule.title());
        fields.put("status", rule.status());
        fields.put("description", rule.description());
        fields.put("messages", String.join("\n", rule.messages()));
        // One entry per named list rather than one lump, so a diff can say
        // WHICH list moved -- "secenekler" and "varsayilan" are different
        // facts and a scenario usually quotes only one of them.
        rule.values().forEach((name, items) ->
                fields.put(VALUE_PREFIX + name, String.join("\n", items)));
        return new Source(Source.RULE, rule.id(), fields);
    }

    public static Source of(DataAlias alias) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("label", alias.label());
        fields.put("description", alias.description());
        fields.put("how", alias.how());
        fields.put("value", alias.value());
        fields.put("blockedBy", alias.blockedBy());
        return new Source(Source.ALIAS, alias.id(), fields);
    }

    /**
     * What moved between the stamp and now.
     *
     * <p>An EMPTY stamp reports nothing at all, and that is the deliberate
     * reading of "nobody has stamped this yet": a scenario imported before
     * this existed would otherwise light up on its first render, and a badge
     * that is on for everything says nothing about anything.
     */
    public static List<SourceChange> compare(List<Source> reviewed, ScenarioFields fields,
                                             ProductModel product) {
        List<SourceChange> changes = new ArrayList<>();
        if (reviewed == null || reviewed.isEmpty() || product == null) {
            return changes;
        }
        Map<String, Source> now = new LinkedHashMap<>();
        capture(fields, product).forEach(source -> now.put(key(source), source));

        for (Source was : reviewed) {
            // Only sources the scenario STILL names. One it dropped is not a
            // finding -- the analyst untagged it on purpose.
            if (fields != null && !names(fields, was)) {
                continue;
            }
            Source current = now.get(key(was));
            if (current == null) {
                changes.add(new SourceChange(was.kind(), was.id(), SourceChange.GONE,
                        was.field("title"), List.of(), List.of(), List.of()));
                continue;
            }
            List<FieldChange> moved = new ArrayList<>();
            LinkedHashSet<String> names = new LinkedHashSet<>(was.fields().keySet());
            names.addAll(current.fields().keySet());
            for (String name : names) {
                String before = was.field(name);
                String after = current.field(name);
                if (!Objects.equals(before, after)) {
                    moved.add(new FieldChange(name, before, after));
                }
            }
            if (moved.isEmpty()) {
                continue;
            }
            changes.add(new SourceChange(current.kind(), current.id(), SourceChange.CHANGED,
                    current.field("title"), moved,
                    valuesOnly(was, current), valuesOnly(current, was)));
        }
        return changes;
    }

    /** Listed in {@code from}'s value lists and not in {@code other}'s. */
    private static List<String> valuesOnly(Source from, Source other) {
        LinkedHashSet<String> mine = values(from);
        mine.removeAll(values(other));
        return List.copyOf(mine);
    }

    private static LinkedHashSet<String> values(Source source) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        source.fields().forEach((name, text) -> {
            if (name.startsWith(VALUE_PREFIX) && !text.isBlank()) {
                all.addAll(List.of(text.split("\n")));
            }
        });
        return all;
    }

    private static boolean names(ScenarioFields fields, Source source) {
        return Source.RULE.equals(source.kind())
                ? fields.rules().contains(source.id())
                : fields.needs().contains(source.id());
    }

    private static String key(Source source) {
        return source.kind() + " " + source.id();
    }
}
