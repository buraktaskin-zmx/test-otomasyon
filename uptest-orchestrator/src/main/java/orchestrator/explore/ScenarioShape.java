package orchestrator.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a cached walk is bound to: the scenario's STEPS, with their quoted
 * values as parameters — not its whole text.
 *
 * <p>Until 13.11.2026 the cache was keyed on a hash of everything the run
 * sent, so renaming the title, rewording a precondition or correcting a
 * Beklenen switched "Hızlı çalıştır" off, although none of those changes what
 * the browser does. The analyst's rule, decided that day:
 * <ul>
 *   <li>title, Ön koşul, Beklenen (final or under a step) — the flow STAYS;
 *       the Beklenen are read from the current text, never from the cache;
 *   <li>a quoted value in a step ({@code "ASAŞ ALÜMİNYUM SAN."} →
 *       {@code "SANKO DBS ÖDEMELERİ"}) — the flow STAYS and the new value is
 *       put where the old one was in the recorded calls;
 *   <li>anything else in a step — its action, its order, unquoted words — the
 *       flow is no longer this scenario's: re-record with "AI ile çalıştır".
 * </ul>
 *
 * <p>A changed value that appears in NO recorded call cannot be honoured:
 * "Ödenecek" picks a row whose recorded target is the row's own text, so
 * replaying it after the step says "Ödendi" would click the old row and call
 * it a pass. Such a change switches the flow off, with the value named.
 */
final class ScenarioShape {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|“([^”]+)”");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ScenarioShape() {
    }

    /** The numbered steps, Beklenen lines left out. */
    static List<String> steps(String text) {
        return StepExpectations.parse(text).stream().map(StepExpectations.Step::text).toList();
    }

    /**
     * The steps with every quoted value replaced by a placeholder, folded:
     * what must be equal for a flow to stay. Null when the text has no
     * numbered steps — nothing to compare, so the caller falls back to the
     * whole text.
     */
    static String shape(String text) {
        List<String> steps = steps(text);
        if (steps.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String step : steps) {
            out.append(TapeRenderer.fold(QUOTED.matcher(step).replaceAll("\"…\""))).append('\n');
        }
        return out.toString();
    }

    /** Old value → new value, for each quoted value of a step that changed. Same shape assumed. */
    static Map<String, String> renames(String saved, String current) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> before = steps(saved);
        List<String> after = steps(current);
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            List<String> was = quoted(before.get(i));
            List<String> now = quoted(after.get(i));
            for (int k = 0; k < Math.min(was.size(), now.size()); k++) {
                if (!was.get(k).equals(now.get(k))) {
                    out.putIfAbsent(was.get(k), now.get(k));
                }
            }
        }
        return out;
    }

    /** The calls with each renamed value replaced, and which renames found no place. */
    record Renamed(List<ReplayCalls.ReplayCall> calls, List<String> unplaced) {
    }

    /**
     * Puts the new values where the old ones were — only where a value IS the
     * old one: a selector's quoted name ({@code [name="X"i]},
     * {@code internal:text="X"s}), an argument equal to it ({@code text},
     * {@code values}, {@code key}), a name the call carries. Never inside a
     * longer string, so "SAN" cannot rewrite "ASAŞ ALÜMİNYUM SAN.".
     */
    static Renamed rename(List<ReplayCalls.ReplayCall> calls, Map<String, String> renames) {
        if (renames.isEmpty()) {
            return new Renamed(calls, List.of());
        }
        Set<String> placed = new LinkedHashSet<>();
        List<ReplayCalls.ReplayCall> out = new ArrayList<>();
        for (ReplayCalls.ReplayCall call : calls) {
            String target = call.target();
            String args = call.args();
            List<String> names = new ArrayList<>(call.names());
            String says = call.says();
            for (Map.Entry<String, String> rename : renames.entrySet()) {
                String old = rename.getKey();
                String now = rename.getValue();
                if (target != null && target.contains("\"" + old + "\"")) {
                    target = target.replace("\"" + old + "\"", "\"" + now + "\"");
                    placed.add(old);
                }
                String renamedArgs = renameArgs(args, old, now, List.of("text", "key"));
                if (!renamedArgs.equals(args)) {
                    args = renamedArgs;
                    placed.add(old);
                }
                // Descriptive only: what is SENT did not change, so these place nothing.
                args = renameArgs(args, old, now, List.of("element"));
                names.replaceAll(name -> name.equals(old) ? now : name);
                if (says != null) {
                    says = says.replace("\"" + old + "\"", "\"" + now + "\"");
                }
            }
            out.add(new ReplayCalls.ReplayCall(call.tool(), target, args, call.dayOffset(), List.copyOf(names),
                    says, call.expected()));
        }
        List<String> unplaced = renames.keySet().stream().filter(old -> !placed.contains(old))
                .map(old -> "\"" + old + "\" → \"" + renames.get(old) + "\"").toList();
        return new Renamed(out, unplaced);
    }

    private static String renameArgs(String args, String old, String now, List<String> keys) {
        if (args == null || args.isBlank()) {
            return args == null ? "" : args;
        }
        try {
            JsonNode root = JSON.readTree(args);
            if (!(root instanceof ObjectNode node)) {
                return args;
            }
            boolean changed = false;
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && value.isTextual() && value.asText().equals(old)) {
                    node.set(key, TextNode.valueOf(now));
                    changed = true;
                }
            }
            JsonNode values = keys.contains("text") ? node.get("values") : null;
            if (values != null && values.isArray()) {
                for (int i = 0; i < values.size(); i++) {
                    if (values.get(i).isTextual() && values.get(i).asText().equals(old)) {
                        ((com.fasterxml.jackson.databind.node.ArrayNode) values).set(i, TextNode.valueOf(now));
                        changed = true;
                    }
                }
            }
            return changed ? node.toString() : args;
        } catch (Exception notJson) {
            return args;
        }
    }

    private static List<String> quoted(String step) {
        List<String> out = new ArrayList<>();
        Matcher m = QUOTED.matcher(step == null ? "" : step);
        while (m.find()) {
            out.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return out;
    }

    /** The calls with their expectations re-attached from the CURRENT text. */
    static List<ReplayCalls.ReplayCall> realign(List<ReplayCalls.ReplayCall> calls, String current) {
        List<ReplayCalls.ReplayCall> fresh = new ArrayList<>();
        calls.forEach(call -> fresh.add(call.expecting(null)));
        ReplayCalls.align(fresh, current);
        return fresh;
    }
}
