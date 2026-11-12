package orchestrator.explore;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A tool result as the model needs to read it: the page's named things, and
 * nothing else.
 *
 * <p><b>Measured, 11.11.2026.</b> After old pages stopped being resent, tool
 * results were still 32,393 tokens of a 124,817-token walk (26%) — and almost
 * all of that is the ONE page kept whole on each turn. A page snapshot from
 * this application is mostly scaffolding: nested {@code generic}, {@code list}
 * and {@code listitem} nodes with no name, there to hold the layout up. The
 * agent acts on things it can name — a button, a link, a field, a row — and on
 * the reference beside them.
 *
 * <p>So three things go, in order of how much they cost:
 * <ol>
 *   <li>the <b>unnamed structural nodes</b>, which are most of the lines;
 *   <li>the <b>{@code ### Ran Playwright code} echo</b>, which repeats back the
 *       call the model just made;
 *   <li>everything past a <b>cap</b>, as before.
 * </ol>
 *
 * <p><b>Nothing with a name is ever dropped</b>, and neither is any line
 * carrying an interactive role — that is the whole safety argument. A node the
 * agent could click, type into or read a value off keeps its line and its
 * reference exactly as the browser reported it; what goes is the box that was
 * holding it.
 *
 * <p>The ACTION TAPE keeps the untouched result (BudgetedTool records before
 * it trims), so "Gherkin oluştur", TapeRenderer and TapeEvidence still read
 * every word the browser sent.
 */
final class LeanResults {

    /** Echoed back at the model: the code its own call produced. */
    private static final Pattern RAN_CODE =
            Pattern.compile("### Ran Playwright code\\s*```js.*?```\\s*", Pattern.DOTALL);

    /** A snapshot line: indent, "- ", role, then optionally a "quoted name". */
    private static final Pattern NODE =
            Pattern.compile("^(\\s*)-\\s+([A-Za-z]+)(\\s+\"(?:[^\"\\\\]|\\\\.)*\")?(.*)$");

    /**
     * Roles that only hold other things. Dropped when they carry no name of
     * their own; kept the moment they do, because a named region is how a
     * person says where they are ("Ödenecekler").
     */
    private static final List<String> SCAFFOLD = List.of(
            "generic", "group", "list", "listitem", "paragraph", "separator", "region",
            "main", "banner", "contentinfo", "navigation", "article", "section",
            "rowgroup", "table", "document", "complementary", "figure", "insertion",
            "deletion", "emphasis", "strong", "time", "note");

    private LeanResults() {
    }

    /**
     * @param cap characters kept of the result; what is cut says so, in the
     *            words BudgetedTool has always used for a page too big to send
     */
    static String summarise(String result, int cap) {
        if (result == null) {
            return null;
        }
        String out = RAN_CODE.matcher(result).replaceAll("");
        out = dropScaffold(out);
        if (out.length() <= cap) {
            return out;
        }
        return out.substring(0, cap)
                + "\n\n[... kesildi. Bu sayfa tek bir cevaba sigmayacak kadar buyuk. "
                + "Aradigin sey burada yoksa TUM sayfayi yeniden okuma: snapshot'i "
                + "target/depth ile daralt.]";
    }

    /**
     * Every line that names something, and every line that can be acted on.
     *
     * A result with no scaffolding in it comes back BYTE FOR BYTE — a click
     * answers "Clicked" and must still answer exactly that. Rebuilding it from
     * its lines would add a newline nobody asked for, which is the kind of
     * difference that turns up later as a failed comparison somewhere else.
     */
    static String dropScaffold(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder kept = new StringBuilder(text.length());
        boolean dropped = false;
        for (int i = 0; i < lines.length; i++) {
            if (keep(lines[i])) {
                if (kept.length() > 0) {
                    kept.append('\n');
                }
                kept.append(lines[i]);
            } else {
                dropped = true;
            }
        }
        return dropped ? kept.toString() : text;
    }

    private static boolean keep(String line) {
        var node = NODE.matcher(line);
        if (!node.matches()) {
            // Headings, URLs, error text, the yaml fences: not nodes, not ours to judge.
            return true;
        }
        boolean named = node.group(3) != null && !node.group(3).isBlank();
        if (named) {
            return true;
        }
        String role = node.group(2).toLowerCase(java.util.Locale.ROOT);
        // A scaffold node with no name holds nothing the agent can ask for.
        // Its children keep their own lines; only the box goes.
        return !SCAFFOLD.contains(role);
    }
}
