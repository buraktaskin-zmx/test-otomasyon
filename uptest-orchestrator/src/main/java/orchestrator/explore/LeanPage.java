package orchestrator.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A page snapshot as one flat line per thing, instead of an indented tree.
 *
 * <p><b>Measured, 12.11.2026.</b> A walk went past 100.000 tokens and was cut
 * off before it reached the button its scenario named. Half of that bill was
 * TOOL RESULTS, and nearly all of those are one single thing: the page, sent
 * again on every turn, as 6.000 characters of YAML tree. Three quarters of
 * those characters are not information:
 *
 * <ul>
 *   <li><b>indentation</b> — a button ten levels deep pays for twenty leading
 *       spaces on its line, every turn;
 *   <li><b>{@code [ref=e8]}</b> — six characters of punctuation around the two
 *       that matter;
 *   <li><b>grid cells</b> — a twenty-row result grid is a hundred and sixty
 *       lines of {@code cell} and {@code generic} wrapping twenty rows of text.
 * </ul>
 *
 * <p>So the tree is rewritten as a list. {@code e8 button "Fatura İptal Et"},
 * one line, no indentation, nothing around the reference. A grid row collapses
 * to its own line with its cells joined, and the controls inside it follow in
 * brackets, because that is how a row gets picked:
 *
 * <pre>
 *   e8 button "Fatura İptal Et"
 *   e28 textbox [Firma Kodu / Müşteri Kodu]
 *   satır e10 955231 | 222,00 TL | Ödenecek (radio e12)
 * </pre>
 *
 * <p><b>It is also MORE informative than the tree, not just smaller.</b> On
 * these screens a form control has no accessible name — the label is a sibling
 * {@code generic} — so the tree made the agent associate two lines by
 * adjacency. Here the label is written beside the control that owns it, in
 * square brackets to say "this came from the page next to it, it is not the
 * element's own name", and the label's own line is then dropped rather than
 * paid for twice.
 *
 * <p><b>What is never dropped</b>, which is the whole safety argument:
 * <ul>
 *   <li>every node with a reference that the agent can act on — its line and
 *       its reference survive verbatim, including a control nested inside a
 *       grid cell;
 *   <li>every node that carries a name or text of its own;
 *   <li>every {@code alert}, {@code status} and {@code dialog}, even inside a
 *       subtree that is otherwise dropped — a validation message is the one
 *       thing a shrunken page must not lose;
 *   <li>everything OUTSIDE the snapshot block: only the fenced tree is
 *       rewritten, so the modal state, the console messages, the page title
 *       and any error text pass through exactly as the browser sent them.
 * </ul>
 *
 * <p>The ACTION TAPE still records the untouched result (BudgetedTool records
 * before it trims), so "Gherkin oluştur", TapeRenderer and TapeEvidence read
 * every word.
 */
final class LeanPage {

    /**
     * The fenced tree inside a tool result.
     *
     * Only this block is replaced. Everything around it — {@code ### Modal
     * state}, {@code ### New console messages}, the URL and the title — is
     * left alone, because a page that is smaller but no longer mentions the
     * open dialog is worse than a page that is big.
     */
    private static final Pattern FENCE =
            Pattern.compile("```+ *yaml\\s*\\R(.*?)\\R?```+", Pattern.DOTALL);

    /** What the agent can click, fill, tick or choose. Never dropped. */
    private static final Set<String> ACTIONABLE = Set.of(
            "button", "link", "textbox", "combobox", "checkbox", "radio", "spinbutton",
            "searchbox", "slider", "switch", "menuitem", "menuitemcheckbox", "menuitemradio",
            "option", "tab", "treeitem");

    /** Controls that hold a value worth printing after an {@code =}. */
    private static final Set<String> VALUE_BEARING =
            Set.of("textbox", "combobox", "spinbutton", "searchbox");

    /**
     * What the application says when it refuses. Kept wherever it is found,
     * chrome and grid cells included.
     */
    private static final Set<String> LOUD =
            Set.of("alert", "alertdialog", "status", "log", "dialog");

    /**
     * The header, the menu and the footer.
     *
     * Dropped on a walk whose menu was opened in code, because the agent has
     * no navigate tool on that path and the menu is the largest single subtree
     * on these pages. Kept when the prelude could not get in, since then the
     * agent navigates itself.
     */
    private static final Set<String> CHROME = Set.of("banner", "navigation", "contentinfo");

    /**
     * Roles that only hold other things. Dropped when they carry no name and
     * no text of their own; kept the moment they do, because a named region is
     * how a person says where they are ("Ödenecekler").
     */
    private static final Set<String> SCAFFOLD = Set.of(
            "generic", "group", "list", "listitem", "paragraph", "separator", "region",
            "main", "banner", "contentinfo", "navigation", "article", "section",
            "rowgroup", "table", "grid", "document", "complementary", "figure", "insertion",
            "deletion", "emphasis", "strong", "time", "note", "img", "cell", "columnheader",
            "rowheader", "list-item");

    private static final String ROW = "row";

    private LeanPage() {
    }

    /**
     * The same result with its tree flattened.
     *
     * @param dropChrome whether the header, menu and footer may go — true only
     *                   when code opened the screen, see {@link #CHROME}
     * @return null when the result carries no snapshot at all, which is the
     *         caller's signal to leave it alone
     */
    static String of(String result, boolean dropChrome) {
        if (result == null || result.isBlank()) {
            return null;
        }
        Matcher fence = FENCE.matcher(result);
        if (!fence.find()) {
            return null;
        }
        AriaSnapshot tree = AriaSnapshot.ofAll(fence.group(1));
        if (tree.isEmpty()) {
            return null;
        }
        String flat = flatten(tree, dropChrome);
        if (flat.isBlank()) {
            return null;
        }
        return new StringBuilder(result).replace(fence.start(), fence.end(), flat).toString();
    }

    /**
     * The first thing on this page that the application is SHOUTING, or null.
     *
     * Used between the steps of a batch ({@link StepRunner}): a validation
     * message or a confirmation dialog appearing halfway through means the
     * remaining steps were planned against a screen that is no longer there,
     * and carrying on would be the blind-plan failure this whole tool has to
     * avoid. Two things are looked for, because either alone would miss a real
     * case: an {@code alert}/{@code status}/{@code dialog} node in the tree,
     * and MCP's own {@code ### Modal state} section, which is how a native
     * browser dialog is reported rather than as a tree node.
     */
    static String complaint(String page) {
        if (page == null || page.isBlank()) {
            return null;
        }
        if (page.contains("### Modal state")) {
            return "bir diyalog açık";
        }
        Matcher fence = FENCE.matcher(page);
        String body = fence.find() ? fence.group(1) : page;
        for (AriaSnapshot.Node node : AriaSnapshot.ofAll(body).nodes()) {
            if (LOUD.contains(role(node)) && !node.caption().isBlank()) {
                return node.caption();
            }
        }
        return null;
    }

    /** Every reference in a flattened page, for the test that nothing was lost. */
    static Set<String> refsIn(String flattened) {
        Set<String> refs = new HashSet<>();
        Matcher token = Pattern.compile("\\b([ef]\\d+[a-z]*\\d*)\\b").matcher(flattened);
        while (token.find()) {
            refs.add(token.group(1));
        }
        return refs;
    }

    private static String flatten(AriaSnapshot tree, boolean dropChrome) {
        List<AriaSnapshot.Node> nodes = tree.nodes();
        boolean[] gone = new boolean[nodes.size()];

        if (dropChrome) {
            for (int i = 0; i < nodes.size(); i++) {
                if (CHROME.contains(role(nodes.get(i)))) {
                    drop(nodes, gone, i, true);
                }
            }
        }

        // A grid row speaks for its cells, so they do not speak for
        // themselves — but a control inside one keeps its reference, appended
        // to the row's line. That is how "Detay" and the row radio survive.
        for (int i = 0; i < nodes.size(); i++) {
            if (ROW.equals(role(nodes.get(i))) && !gone[i]) {
                drop(nodes, gone, i, false);
            }
        }

        // The label of an unnamed control is written beside the control, so
        // its own line is not printed as well. Only a SCAFFOLD node is
        // swallowed this way: a button that happens to sit before a textbox
        // keeps its line, because it is something the agent can press.
        Set<Integer> labels = new HashSet<>();
        for (AriaSnapshot.Node node : nodes) {
            if (node.ref().isEmpty() || !ACTIONABLE.contains(role(node)) || !node.name().isBlank()) {
                continue;
            }
            int source = tree.labelSourceOf(node.ref());
            if (source >= 0 && SCAFFOLD.contains(role(nodes.get(source)))) {
                labels.add(source);
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            AriaSnapshot.Node node = nodes.get(i);
            boolean loud = LOUD.contains(role(node));
            if ((gone[i] || labels.contains(i)) && !loud) {
                continue;
            }
            String line = ROW.equals(role(node)) ? row(nodes, i) : one(tree, node);
            if (line != null && !line.isBlank()) {
                out.append(line).append('\n');
            }
        }
        return out.length() == 0 ? "" : out.substring(0, out.length() - 1);
    }

    /**
     * Marks a subtree as spoken for.
     *
     * @param withRoot whether the node itself goes too — a chrome container
     *                 does, a grid row does not, because the row still prints
     *                 its own line
     */
    private static void drop(List<AriaSnapshot.Node> nodes, boolean[] gone, int index, boolean withRoot) {
        if (withRoot) {
            gone[index] = true;
        }
        for (int inside : descendants(nodes, index)) {
            gone[inside] = true;
        }
    }

    /** One element: {@code e8 button "Fatura İptal Et"}. */
    private static String one(AriaSnapshot tree, AriaSnapshot.Node node) {
        String role = role(node);
        boolean actionable = ACTIONABLE.contains(role);
        String caption = node.name();
        String value = node.text();
        boolean borrowed = false;

        if (caption.isBlank() && actionable && !node.ref().isEmpty()) {
            String label = tree.labelOf(node.ref());
            // labelOf falls back to the control's own caption, which for an
            // unnamed box IS its value — printing that as the label would
            // claim the page says "955231" where it says "Fatura No".
            if (!label.isBlank() && !label.equals(value)) {
                caption = label;
                borrowed = true;
            }
        }
        if (caption.isBlank() && !value.isBlank()) {
            caption = value;
            value = "";
        }
        if (caption.isBlank() && !actionable && !LOUD.contains(role)) {
            // An unnamed box holding nothing of its own: its children keep
            // their own lines, only the box goes.
            return null;
        }
        if (caption.isBlank() && SCAFFOLD.contains(role)) {
            return null;
        }

        StringBuilder line = new StringBuilder();
        if (!node.ref().isEmpty()) {
            line.append(node.ref()).append(' ');
        }
        line.append(role);
        if (!caption.isBlank()) {
            line.append(borrowed ? " [" : " \"").append(caption).append(borrowed ? ']' : '"');
        }
        if (!value.isBlank() && (VALUE_BEARING.contains(role) || !actionable)) {
            line.append(" = \"").append(value).append('"');
        }
        return line.toString();
    }

    /**
     * One grid row: {@code satır e10 955231 | 222,00 TL | Ödenecek (radio e12)}.
     *
     * The cells are preferred over the row's own accessible name, which on
     * these grids is every cell run together ("955231 222,00 TL Ödenecek") and
     * so cannot be read back column by column.
     */
    private static String row(List<AriaSnapshot.Node> nodes, int index) {
        List<String> cells = new ArrayList<>();
        List<String> controls = new ArrayList<>();
        for (int inside : descendants(nodes, index)) {
            AriaSnapshot.Node child = nodes.get(inside);
            String role = role(child);
            if (ACTIONABLE.contains(role)) {
                StringBuilder control = new StringBuilder(role);
                if (!child.ref().isEmpty()) {
                    control.append(' ').append(child.ref());
                }
                if (!child.name().isBlank()) {
                    control.append(" \"").append(child.name()).append('"');
                }
                controls.add(control.toString());
                continue;
            }
            String caption = child.caption();
            // A cell and the generic inside it report the same text; two
            // different columns holding the same value are a fact about the
            // grid, so only ADJACENT repeats go.
            if (!caption.isBlank() && (cells.isEmpty() || !cells.get(cells.size() - 1).equals(caption))) {
                cells.add(caption);
            }
        }
        AriaSnapshot.Node row = nodes.get(index);
        // The row's own accessible name is every cell run together, so the
        // cells are normally the better reading — they keep the columns apart.
        // But when the name says something the cells do not, the name wins:
        // a snapshot whose cells carry no text of their own would otherwise
        // turn a priced, dated invoice row into its invoice number alone.
        String caption = row.caption();
        if (!caption.isBlank() && !covers(cells, caption)) {
            cells.clear();
            cells.add(caption);
        }
        if (cells.isEmpty() && controls.isEmpty()) {
            return null;
        }

        StringBuilder line = new StringBuilder("satır");
        if (!row.ref().isEmpty()) {
            line.append(' ').append(row.ref());
        }
        if (!cells.isEmpty()) {
            line.append(' ').append(String.join(" | ", cells));
        }
        if (!controls.isEmpty()) {
            line.append(" (").append(String.join(", ", controls)).append(')');
        }
        return line.toString();
    }

    /**
     * Whether the cells, read together, already say everything the row's own
     * name says. Punctuation and spacing differ between the two — the name
     * joins cells with a single space, we join them with a bar — so the
     * comparison is made on the characters that carry meaning.
     */
    private static boolean covers(List<String> cells, String caption) {
        if (cells.isEmpty()) {
            return false;
        }
        return bare(String.join(" ", cells)).contains(bare(caption));
    }

    private static String bare(String text) {
        return text.replaceAll("[\s|]+", "");
    }

    /** Every node inside this one, by indentation. */
    private static List<Integer> descendants(List<AriaSnapshot.Node> nodes, int index) {
        List<Integer> inside = new ArrayList<>();
        int indent = nodes.get(index).indent();
        for (int i = index + 1; i < nodes.size() && nodes.get(i).indent() > indent; i++) {
            inside.add(i);
        }
        return inside;
    }

    private static String role(AriaSnapshot.Node node) {
        return node.role().toLowerCase(Locale.ROOT);
    }
}
