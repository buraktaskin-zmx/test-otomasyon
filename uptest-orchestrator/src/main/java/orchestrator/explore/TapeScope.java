package orchestrator.explore;

import orchestrator.explore.PlaywrightLine.Act;
import orchestrator.explore.TapeRenderer.Call;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static orchestrator.explore.TapeRenderer.fold;

/**
 * The snapshots a reference can still be looked up in.
 *
 * Newest first, and a handful rather than one: the agent takes a reference
 * from whichever reading came last, and that is sometimes a
 * {@code browser_find} result rather than a full snapshot — the "Detay"
 * link of this walk was found that way. Both are the same tree in the same
 * shape, so both are parsed.
 */
final class TapeScope {

    private static final int REMEMBERED = 6;

    private final Deque<AriaSnapshot> recent = new ArrayDeque<>();

    void remember(Call call) {
        if (call.result() == null) {
            return;
        }
        AriaSnapshot parsed = AriaSnapshot.of(call.result());
        if (parsed.isEmpty()) {
            return;
        }
        recent.addFirst(parsed);
        while (recent.size() > REMEMBERED) {
            recent.removeLast();
        }
        // The fullest form seen: the screen the fields were filled on is
        // the one with the most labelled boxes, and it is long gone from
        // `recent` by the time the walk ends.
        List<String> labels = parsed.controlLabels();
        if (labels.size() > fieldOrder.size()) {
            fieldOrder = labels;
        }
    }

    private List<String> fieldOrder = List.of();

    /** Where the screen prints this field, or -1 when it never did. */
    int rankOf(String label) {
        for (int i = 0; i < fieldOrder.size(); i++) {
            if (fold(fieldOrder.get(i)).equals(fold(label))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * What to call the element: the echoed name when it was EXACT, the
     * snapshot's own otherwise. See the class note on the shortened
     * "APPLE A.Ş".
     */
    String caption(Act act, String ref) {
        if (act.exact() && !act.name().isBlank()) {
            return act.name();
        }
        for (AriaSnapshot snapshot : recent) {
            if (snapshot.node(ref).isPresent()) {
                String plain = snapshot.plainName(ref);
                if (!plain.isBlank()) {
                    return plain;
                }
            }
        }
        return act.name();
    }

    /**
     * Reads a little way past a call, so the EFFECT of it can be seen.
     *
     * Two is enough: a choice is followed by a settle and then a
     * snapshot. More would start pulling in the next screen.
     */
    void peek(List<Call> calls, int from) {
        int seen = 0;
        for (int i = from + 1; i < calls.size() && seen < 2; i++) {
            int before = recent.size();
            remember(calls.get(i));
            if (recent.size() != before) {
                seen++;
            }
        }
    }

    /**
     * The label of the field now DISPLAYING this value.
     *
     * The reliable way to name a dropdown: after the choice, the value
     * sits inside its own control, so climbing to the label starts from
     * the right place. Climbing from the control BEFORE it was opened
     * walks into whatever label precedes it in the form, which on the
     * DBS form meant a currency landing under "Orijinal Fatura Tutarı".
     *
     * Options are skipped on the way: the list that was open still
     * carries the same text, and its label is the listbox's neighbour
     * rather than the field's.
     */
    String labelOfChosen(String chosen) {
        for (AriaSnapshot snapshot : recent) {
            for (AriaSnapshot.Node node : snapshot.nodes()) {
                if ("option".equals(node.role()) || !fold(node.caption()).equals(fold(chosen))) {
                    continue;
                }
                // Only a CONTROL can be the field that now holds the
                // value. The snapshots are read newest first, and the one
                // after a page-changing choice is the NEXT screen, where
                // the same text is plain print — "Kurum: Sanko DBS
                // Ödemeleri" — and its label named the dropdown. The
                // control showing the value sits in an older snapshot.
                if (!"textbox".equals(node.role()) && !"combobox".equals(node.role())
                        && !"spinbutton".equals(node.role())) {
                    continue;
                }
                String label = snapshot.labelOf(node.ref());
                if (!label.isBlank() && !fold(label).equals(fold(chosen))) {
                    return label;
                }
            }
        }
        return "";
    }

    /**
     * The candidate, spelled as the SCREEN spells it, or blank when no
     * snapshot carries it as a label of a control.
     *
     * A bare text node is not enough: "Kurum" is printed on the form as
     * a heading-like caption beside a value, and is not a field. Only a
     * label whose next sibling is a control counts.
     */
    String groundedLabel(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        // The agent decorates the label with whatever word it likes
        // ("Firma Seçimi combobox", "… dropdown", "… alanı"), so the label
        // only has to be CONTAINED in what it said, and the longest one
        // wins: "Fatura Tarihi" must not be taken for "Tarih".
        String said = fold(candidate);
        String best = "";
        for (AriaSnapshot snapshot : recent) {
            for (AriaSnapshot.Node node : snapshot.nodes()) {
                String caption = node.caption();
                if (caption.length() >= 3 && said.contains(fold(caption))
                        && caption.length() > best.length() && snapshot.labelsAControl(node.ref())) {
                    best = caption;
                }
            }
        }
        return best;
    }

    /** The label that introduces the box this reference sits in, or blank. */
    String ownerLabel(String ref) {
        for (AriaSnapshot snapshot : recent) {
            String label = snapshot.ownerLabel(ref);
            if (!label.isBlank()) {
                return label;
            }
        }
        return "";
    }

    /** The field's label, for the steps that take one. */
    String labelOf(String ref, String fallback) {
        for (AriaSnapshot snapshot : recent) {
            if (snapshot.node(ref).isPresent()) {
                String label = snapshot.labelOf(ref);
                if (!label.isBlank()) {
                    return label;
                }
            }
        }
        return fallback;
    }

    String rowIdentifier(String ref) {
        for (AriaSnapshot snapshot : recent) {
            if (snapshot.node(ref).isPresent()) {
                String identifier = snapshot.rowIdentifier(ref);
                if (!identifier.isBlank()) {
                    return identifier;
                }
            }
        }
        return "";
    }

    /**
     * The label/value pairs of the screen the walk ended on.
     *
     * Newest first and merged, because the dialog a scenario asserts on
     * is usually the last thing read while the grid behind it was read a
     * call or two earlier — and an expectation may name either.
     */
    Map<String, String> labelledValues() {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (AriaSnapshot snapshot : recent) {
            snapshot.labelledValues().forEach(pairs::putIfAbsent);
        }
        return pairs;
    }

    /** Whether the element sits in a grid row — see AriaSnapshot#insideRow. */
    boolean insideRow(String ref) {
        for (AriaSnapshot snapshot : recent) {
            if (snapshot.node(ref).isPresent()) {
                return snapshot.insideRow(ref);
            }
        }
        return false;
    }

    String roleOf(String ref) {
        for (AriaSnapshot snapshot : recent) {
            Optional<AriaSnapshot.Node> node = snapshot.node(ref);
            if (node.isPresent()) {
                return node.get().role();
            }
        }
        return "";
    }
}
