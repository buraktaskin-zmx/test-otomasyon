package orchestrator.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code browser_snapshot} result, read back as a tree of references.
 *
 * <p>The agent names elements by a snapshot reference — {@code e42} — and a
 * reference is dead the moment the page changes, so a recorded tool call means
 * nothing on its own. What gives it meaning is the snapshot the model was
 * looking at when it chose that reference, which {@link ActionTape} stores
 * beside the call for exactly this purpose. This class turns that text back
 * into something a Gherkin line can be written from.
 *
 * <p><b>Two layers of wrapping.</b> The MCP result is a JSON array of content
 * blocks, and the tree sits inside one block's {@code text} as a fenced YAML
 * section after a {@code ### Snapshot} heading. Both are peeled here rather
 * than by the caller, because getting either wrong produces an empty tree and
 * no error.
 *
 * <p><b>Why a hand-written parser and not a YAML one.</b> The block is
 * YAML-shaped but not YAML: {@code - generic [ref=e27]: Firma Kodu} has a key
 * that is not a string a loader would accept, and duplicate keys abound. The
 * grammar that matters is one line, and it is small:
 *
 * <pre>
 *   - role "accessible name" [attr] [ref=eNN] [attr]: trailing text
 *     │    │                  │                        │
 *     │    │                  └─ any order, ref among  └─ a generic's own text
 *     │    └─ absent on most form controls (see below)   them
 *     └─ link, button, cell, generic, textbox, row, …
 * </pre>
 *
 * <p><b>The finding that shapes this class:</b> on these screens a form
 * control has NO accessible name. The login page reads
 * {@code - generic [ref=e27]: Firma Kodu / Müşteri Kodu} followed by
 * {@code - textbox [ref=e28]} — the label is a SIBLING, not the box's name.
 * That is the accessibility-tree view of what CommonActions already works
 * around in the DOM ("a label this application renders as a plain span with no
 * programmatic association to its input"), and it is why {@link #labelOf}
 * exists next to {@link #nameOf}.
 */
public final class AriaSnapshot {

    /**
     * One line of the tree.
     *
     * @param ref    the snapshot reference, e.g. {@code e42} or {@code f1e719}
     * @param role   button, link, cell, generic, textbox, row, …
     * @param name   the accessible name, blank when the element has none
     * @param text   a generic's own text, i.e. what follows the colon
     * @param indent leading spaces, which is the only thing that carries the
     *               tree's shape
     * @param parent index of the enclosing node, or -1 at the root
     */
    public record Node(String ref, String role, String name, String text,
                       int indent, int parent) {

        /** Whatever a person would read off this line, name first. */
        public String caption() {
            return !name.isBlank() ? name : text;
        }
    }

    /**
     * A reference is a letter-and-digit token. Usually {@code e42}, but a page
     * with an iframe produces {@code f1e719} — frame one, element 719 — and a
     * walk that met one was the first to show the second form. Matching the
     * whole token rather than {@code e\d+} keeps both.
     */
    private static final Pattern REF = Pattern.compile("\\[ref=([A-Za-z0-9]+)]");

    /**
     * indent, role, optional "name", the attribute run, optional trailing text.
     *
     * The attribute run is matched as a block and searched afterwards because
     * the reference is not always last: a heading carries {@code [level=1]}
     * before it, a radio carries {@code [checked]}, a Select2's hidden native
     * control carries {@code [aria-hidden]}.
     */
    private static final Pattern LINE = Pattern.compile(
            "^(\\s*)-\\s+([A-Za-z]+)"
                    + "(?:\\s+\"((?:[^\"\\\\]|\\\\.)*)\")?"
                    + "((?:\\s*\\[[^\\]]*])*)"
                    + "\\s*:?\\s*(.*)$");

    /**
     * A whole line wrapped in single quotes, which YAML does when the scalar
     * would otherwise be ambiguous.
     *
     * Every sortable column header on these grids is in that shape, because
     * its accessible name contains a colon: {@code - 'columnheader "Bayi
     * Kodu: activate to sort column ascending" [ref=f1e599]':}. Left wrapped,
     * the line matches nothing, the node is dropped, and its child attaches
     * to the GRANDparent — which quietly made all six column headers siblings
     * and had "Bayi Kodu" report the value of the header next to it.
     */
    private static final Pattern WRAPPED = Pattern.compile("^(\\s*-\\s+)'(.*)'(:?)\\s*$");

    /** Where the tree starts inside the tool result's text block. */
    private static final String SNAPSHOT_HEADING = "### Snapshot";

    private final List<Node> nodes;
    private final Map<String, Integer> byRef;

    private AriaSnapshot(List<Node> nodes, Map<String, Integer> byRef) {
        this.nodes = nodes;
        this.byRef = byRef;
    }

    /**
     * Parses a {@code browser_snapshot} result, wrappers and all.
     *
     * Never throws: a result that is not a snapshot — a refusal sentence, an
     * error, a truncated tail — yields an empty tree, and the caller asks it
     * questions it simply cannot answer. A renderer that stopped on the first
     * unparseable result would lose a whole walk over one odd entry.
     */
    public static AriaSnapshot of(String toolResult) {
        List<Node> nodes = new ArrayList<>();
        Map<String, Integer> byRef = new LinkedHashMap<>();
        if (toolResult == null || toolResult.isBlank()) {
            return new AriaSnapshot(nodes, byRef);
        }

        List<Integer> stack = new ArrayList<>();
        for (String line : body(toolResult).split("\\R")) {
            if (line.isBlank() || line.contains("/url:")) {
                continue;
            }
            Matcher match = LINE.matcher(unwrap(line));
            if (!match.matches()) {
                continue;
            }
            Matcher ref = REF.matcher(match.group(4) == null ? "" : match.group(4));
            if (!ref.find()) {
                continue;
            }

            int indent = match.group(1).length();
            while (!stack.isEmpty() && nodes.get(stack.get(stack.size() - 1)).indent() >= indent) {
                stack.remove(stack.size() - 1);
            }
            int parent = stack.isEmpty() ? -1 : stack.get(stack.size() - 1);

            Node node = new Node(ref.group(1), match.group(2),
                    unescape(match.group(3)), unquote(unescape(match.group(5))), indent, parent);
            byRef.putIfAbsent(node.ref(), nodes.size());
            stack.add(nodes.size());
            nodes.add(node);
        }
        return new AriaSnapshot(nodes, byRef);
    }

    /**
     * The tree, with both wrappers removed.
     *
     * The JSON array is peeled by hand rather than with a parser: the only
     * thing wanted out of it is the one long string, finding it needs no
     * object model, and a malformed block should degrade to "no tree" instead
     * of an exception on a path that is only ever explaining a past run.
     */
    private static String body(String toolResult) {
        String text = toolResult;
        int heading = text.indexOf(SNAPSHOT_HEADING);
        if (heading >= 0) {
            text = text.substring(heading + SNAPSHOT_HEADING.length());
        }
        // Inside a JSON string the tree's line breaks are still escaped.
        if (!text.contains("\n") && text.contains("\\n")) {
            text = text.replace("\\n", "\n").replace("\\\"", "\"");
        }
        return text;
    }

    /** Takes a single-quoted line back to the plain one, {@code ''} and all. */
    private static String unwrap(String line) {
        Matcher wrapped = WRAPPED.matcher(line);
        if (!wrapped.matches()) {
            return line;
        }
        return wrapped.group(1) + wrapped.group(2).replace("''", "'") + wrapped.group(3);
    }

    private static String unescape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\\"", "\"").replace("\\n", " ").trim();
    }

    /**
     * Drops the quotes the dump puts around a value it considers ambiguous.
     *
     * Most trailing text arrives bare ({@code generic [ref=e658]: Ana Firma
     * Adı}), but a value that could be read as something other than a string
     * comes quoted ({@code generic [ref=e669]: "8***7"}). Keeping the quotes
     * would put them into a Gherkin argument, where they would be compared
     * against a screen that does not show them.
     */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    /** Every node, in document order. */
    public List<Node> nodes() {
        return List.copyOf(nodes);
    }

    /** True when nothing could be read out of the result. */
    /** The labels of this screen's fields, in the order the page prints them. */
    public List<String> controlLabels() {
        List<String> labels = new ArrayList<>();
        for (Node node : nodes) {
            if (!node.caption().isBlank() && labelsAControl(node.ref())) {
                labels.add(node.caption());
            }
        }
        return labels;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public Optional<Node> node(String ref) {
        Integer index = byRef.get(ref);
        return index == null ? Optional.empty() : Optional.of(nodes.get(index));
    }

    /** The element's accessible name, which is what {@code click} matches on. */
    public String nameOf(String ref) {
        return node(ref).map(Node::name).orElse("");
    }

    /**
     * The label a value-bearing step would name, for a control that has none
     * of its own.
     *
     * Looks among the control's SIBLINGS, nearest first, for one carrying
     * text. That is the shape every form on these screens uses — a wrapper
     * holding {label, control} — and it is the same relationship
     * CommonActions reaches through {@code ancestor::div[fieldcontainer]}.
     *
     * Falls back to the element's own name, so a control that does have one
     * (a Select2 renders its chosen value as the textbox's name) still
     * answers something rather than nothing.
     */
    public String labelOf(String ref) {
        Integer start = byRef.get(ref);
        if (start == null) {
            return "";
        }
        // Climbing, because the control is not always a direct sibling of its
        // label: a Select2 renders as combobox > textbox, so the textbox's
        // only sibling is nothing at all and the label sits one level up,
        // beside the combobox. Three levels covers every wrapper these screens
        // use and stops well short of the page, where the nearest text above
        // anything is a menu item.
        int index = start;
        for (int level = 0; level < 3 && index >= 0; level++) {
            String label = precedingText(index);
            if (!label.isBlank()) {
                return label;
            }
            index = nodes.get(index).parent();
        }
        return nodes.get(start).caption();
    }

    /** The nearest earlier sibling carrying text, or blank. */
    private String precedingText(int index) {
        Node target = nodes.get(index);
        for (int i = index - 1; i >= 0; i--) {
            Node candidate = nodes.get(i);
            if (candidate.parent() != target.parent()) {
                if (candidate.indent() < target.indent()) {
                    return "";
                }
                continue;
            }
            if (!candidate.text().isBlank()) {
                return candidate.text();
            }
            if (!candidate.name().isBlank()) {
                return candidate.name();
            }
        }
        return "";
    }

    /**
     * Whether this node is the LABEL of a control: its next sibling is a box
     * the user can fill or open.
     *
     * Separates a field's caption from a caption that merely sits beside a
     * value. "Kurum" on a form is followed by its value as plain text, so it
     * is not a field; "Firma Seçimi" is followed by a combobox, so it is.
     */
    public boolean labelsAControl(String ref) {
        Integer index = byRef.get(ref);
        if (index == null) {
            return false;
        }
        Node node = nodes.get(index);
        for (int i = index + 1; i < nodes.size(); i++) {
            Node next = nodes.get(i);
            if (next.indent() <= node.indent() && next.parent() != node.parent()) {
                return false;
            }
            if (next.parent() == node.parent()) {
                return "combobox".equals(next.role()) || "textbox".equals(next.role())
                        || "spinbutton".equals(next.role());
            }
        }
        return false;
    }

    /** The nodes directly inside this one, in document order. */
    public List<Node> childrenOf(String ref) {
        Integer index = byRef.get(ref);
        if (index == null) {
            return List.of();
        }
        List<Node> children = new ArrayList<>();
        for (int i = index + 1; i < nodes.size(); i++) {
            if (nodes.get(i).indent() <= nodes.get(index).indent()) {
                break;
            }
            if (nodes.get(i).parent() == index) {
                children.add(nodes.get(i));
            }
        }
        return children;
    }

    /**
     * The accessible name with a decoration stripped off the end.
     *
     * The NAKİT YÖNETİMİ menu marks new screens with a badge, so the link that
     * opens Firma Fatura İşlemleri is named "Firma Fatura İşlemleri YENİ" —
     * and a step carrying that name would go stale the day the badge is
     * removed. The badge is its own child node, so it can be subtracted rather
     * than guessed at: no list of words to maintain, and a caption that
     * genuinely ends in "YENİ" is left alone because it has no such child.
     */
    public String plainName(String ref) {
        String name = nameOf(ref);
        if (name.isBlank()) {
            return name;
        }
        for (Node child : childrenOf(ref)) {
            String decoration = child.caption();
            if (!decoration.isBlank() && !decoration.equals(name) && name.endsWith(decoration)) {
                return name.substring(0, name.length() - decoration.length()).trim();
            }
        }
        return name;
    }

    /**
     * A cell value that tells this grid row apart from the others.
     *
     * {@code select row} matches on any column, so any cell would drive the
     * step — but not every cell IDENTIFIES the row. On Firma Fatura İşlemleri
     * the dealer code is the same in all five rows, so a step built from the
     * first cell would match five rows and pick whichever came first. The
     * invoice number is unique, and which column that is cannot be known in
     * advance: it is read off the grid by comparing the row against its
     * siblings.
     *
     * Falls back to the first non-empty cell when nothing is unique, because a
     * step that names a real value and fails loudly beats no step at all.
     */
    public String rowIdentifier(String ref) {
        Integer index = byRef.get(ref);
        if (index == null) {
            return "";
        }
        int row = ancestorWithRole(index, "row");
        if (row < 0) {
            return "";
        }
        int group = nodes.get(row).parent();

        List<String> mine = cellTexts(row);
        Set<String> elsewhere = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (i != row && nodes.get(i).parent() == group && "row".equals(nodes.get(i).role())) {
                elsewhere.addAll(cellTexts(i));
            }
        }
        return mine.stream().filter(cell -> !cell.isBlank() && !elsewhere.contains(cell)).findFirst()
                .orElseGet(() -> mine.stream().filter(cell -> !cell.isBlank()).findFirst().orElse(""));
    }

    /**
     * Every label on this screen that has a value sitting beside it.
     *
     * Used to decide whether an expectation an analyst WROTE can be checked on
     * the screen the walk ended on: the label has to exist there, or the
     * verify would spend its timeout on a field that was never shown. The
     * values come along for a cross-check — never to become the expectation,
     * because an expectation read off the application is a bug promoted to a
     * passing test.
     */
    public Map<String, String> labelledValues() {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (node.text().isBlank()) {
                continue;
            }
            String value = valueBeside(node.ref());
            if (!value.isBlank()) {
                pairs.putIfAbsent(node.text(), value);
            }
        }
        return pairs;
    }

    /**
     * Whether this element sits inside a grid row.
     *
     * The one question that tells {@code select row} from {@code choose}: a
     * radio in a result grid is picked by its row's value, a radio in a plain
     * form section is picked by the text beside it. CommonActions draws the
     * same line in the DOM — selectRow matches on ancestor::td then
     * ancestor::tr, and chooseOption exists precisely because that misses a
     * radio with neither.
     */
    public boolean insideRow(String ref) {
        Integer index = byRef.get(ref);
        return index != null && ancestorWithRole(index, "row") >= 0;
    }

    private int ancestorWithRole(int index, String role) {
        for (int at = index; at >= 0; at = nodes.get(at).parent()) {
            if (role.equals(nodes.get(at).role())) {
                return at;
            }
        }
        return -1;
    }

    private List<String> cellTexts(int row) {
        List<String> texts = new ArrayList<>();
        for (int i = row + 1; i < nodes.size(); i++) {
            if (nodes.get(i).indent() <= nodes.get(row).indent()) {
                break;
            }
            if (nodes.get(i).parent() == row && "cell".equals(nodes.get(i).role())) {
                texts.add(nodes.get(i).caption());
            }
        }
        return texts;
    }

    /**
     * The value shown beside a label, inside one cell.
     *
     * The "Fatura Bilgileri" dialog renders a row as
     * {@code cell "Bayi Kodu 81234567"} wrapping {@code generic: Bayi Kodu}
     * and {@code generic: 81234567} — label and value as siblings. Given the
     * LABEL's reference this returns the value; given anything else, blank.
     */
    public String valueBeside(String ref) {
        Optional<Node> found = node(ref);
        if (found.isEmpty()) {
            return "";
        }
        Node label = found.get();
        int index = byRef.get(ref);

        for (int i = index + 1; i < nodes.size(); i++) {
            Node candidate = nodes.get(i);
            if (candidate.parent() != label.parent()) {
                if (candidate.indent() <= label.indent()) {
                    break;
                }
                continue;
            }
            if (!candidate.text().isBlank()) {
                return candidate.text();
            }
        }
        return "";
    }
}
