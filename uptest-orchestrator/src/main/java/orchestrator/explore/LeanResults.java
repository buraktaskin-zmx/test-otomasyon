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
        return summarise(result, cap, "");
    }

    /**
     * @param scenario the scenario text, folded as BudgetedTool folds it: an
     *                 option it names is never hidden from a long list
     */
    static String summarise(String result, int cap, String scenario) {
        if (result == null) {
            return null;
        }
        String out = RAN_CODE.matcher(unwrap(result)).replaceAll("");
        out = dropScaffold(out);
        out = tidy(out, scenario == null ? "" : scenario);
        if (out.length() <= cap) {
            return out;
        }
        return out.substring(0, cap)
                + "\n\n[... kesildi. Bu sayfa tek bir cevaba sigmayacak kadar buyuk. "
                + "Aradigin sey burada yoksa TUM sayfayi yeniden okuma: snapshot'i "
                + "target/depth ile daralt.]";
    }

    /** Lines that tell the agent nothing it can act on. */
    private static final List<Pattern> NOISE = List.of(
            // Every link carries one, and on this application it is "#" or
            // javascript:void(0) — the address is never what is clicked.
            Pattern.compile("^\\s*- /url:.*$"),
            Pattern.compile("^\\s*- Page Title:.*$"),
            Pattern.compile("^\\s*- Console:.*$"),
            Pattern.compile("^\\s*### Events\\s*$"),
            Pattern.compile("^\\s*- New console entries:.*$"),
            // The click's own answer points at a FILE holding the page; the
            // page itself follows (AiRunPolicy), so the pointer is dead weight.
            Pattern.compile("^\\s*- \\[Snapshot]\\(.*\\)\\s*$"));

    /** A sortable grid header: {@code columnheader "Bayi Kodu: activate to sort column ascending"}. */
    private static final Pattern SORT_HINT = Pattern.compile(": activate to sort column (?:ascending|descending)");

    /** A line the dump wrapped in single quotes because of the colon above. */
    private static final Pattern QUOTED_LINE = Pattern.compile("^(\\s*-\\s+)'(.*)'(:?)\\s*$");

    /** {@code - option "ASAŞ ALÜMİNYUM SAN." [ref=f3e493]} */
    private static final Pattern OPTION = Pattern.compile("^(\\s*)- option \"((?:[^\"\\\\]|\\\\.)*)\"(.*)$");

    /** A child whose whole content is text: {@code - generic [ref=…]: Bayi Kodu} or {@code - text: …}. */
    private static final Pattern TEXT_CHILD =
            Pattern.compile("^(\\s*)- (?:generic(?: \\[[^]]*])*|text): (.+)$");

    /** A node line, for its indent and name. */
    private static final Pattern NAMED_NODE =
            Pattern.compile("^(\\s*)- [A-Za-z]+ \"((?:[^\"\\\\]|\\\\.)*)\".*$");

    /** A list longer than this is shortened. */
    static final int OPTIONS_SHOWN = 15;

    /** Options kept from the top of a shortened list, after the chosen and the named ones. */
    private static final int OPTIONS_HEAD = 3;

    /**
     * What is left after the scaffolding, without the noise.
     *
     * <p><b>Measured 12.11.2026</b> on tape 2628c4fd. The Firma Fatura
     * İşlemleri page after a row is picked is 10.413 characters: {@code /url}
     * lines 838, {@code [cursor=pointer]} 833, sortable headers saying
     * "activate to sort column ascending" and then their own name again as a
     * child, the console counters. The page with Firma Seçimi OPEN is 13.714,
     * and 6.860 of it — half — is the 113 firms of one dropdown, of which the
     * scenario names one.
     *
     * <p>Nothing here removes a reference the agent could act on, with one
     * deliberate exception: a list of more than {@value #OPTIONS_SHOWN}
     * options keeps the chosen one, every option the SCENARIO names, and the
     * first few, and says how many it left out and how to reach them (typing
     * into the focused search box, which needs no reference). That is the
     * analyst's idea of "filter by what the step names" applied where it is
     * safe: the step that picks an option names it, and when it does not,
     * the way to the rest is printed on the spot. A blanket filter by name
     * was tried on the whole page in 40a2a7f and hid the menu link the next
     * action needed; it is not repeated here.
     */
    static String tidy(String text, String scenario) {
        String[] lines = text.split("\n", -1);
        List<String> out = new java.util.ArrayList<>(lines.length);
        // Ancestors with a name, nearest last: [indent, name].
        java.util.ArrayDeque<String[]> named = new java.util.ArrayDeque<>();
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (NOISE.stream().anyMatch(p -> p.matcher(line).matches())) {
                changed = true;
                continue;
            }
            String cleaned = line.replace(" [cursor=pointer]", "");
            cleaned = SORT_HINT.matcher(cleaned).replaceAll("");
            var quoted = QUOTED_LINE.matcher(cleaned);
            if (!cleaned.equals(line) && quoted.matches()) {
                cleaned = quoted.group(1) + quoted.group(2).replace("''", "'") + quoted.group(3);
            }

            int indent = indentOf(cleaned);
            while (!named.isEmpty() && Integer.parseInt(named.peekLast()[0]) >= indent) {
                named.pollLast();
            }

            // A child that only repeats what its parent is already called.
            var child = TEXT_CHILD.matcher(cleaned);
            if (child.matches() && !named.isEmpty()
                    && fold(named.peekLast()[1]).contains(fold(child.group(2)).replace("\"", ""))) {
                changed = true;
                continue;
            }

            // A long run of options.
            if (OPTION.matcher(cleaned).matches()) {
                int end = i;
                while (end < lines.length && OPTION.matcher(lines[end]).matches()
                        && indentOf(lines[end]) == indent) {
                    end++;
                }
                if (end - i > OPTIONS_SHOWN) {
                    int hidden = 0;
                    for (int k = i; k < end; k++) {
                        var option = OPTION.matcher(lines[k].replace(" [cursor=pointer]", ""));
                        option.matches();
                        boolean keep = k - i < OPTIONS_HEAD
                                || option.group(3).contains("[selected]")
                                || (!scenario.isEmpty() && scenario.contains(fold(option.group(2))));
                        if (keep) {
                            out.add(lines[k].replace(" [cursor=pointer]", ""));
                        } else {
                            hidden++;
                        }
                    }
                    out.add(" ".repeat(indent) + "- (" + hidden + " seçenek daha gösterilmedi. Aradığın"
                            + " burada yoksa liste açıkken adını yaz: browser_press_sequentially, hedefsiz)");
                    i = end - 1;
                    changed = true;
                    continue;
                }
            }

            var node = NAMED_NODE.matcher(cleaned);
            if (node.matches()) {
                named.addLast(new String[]{String.valueOf(indent), node.group(2)});
            }
            if (!cleaned.equals(line)) {
                changed = true;
            }
            out.add(cleaned);
        }
        if (!changed) {
            return text;
        }
        // A heading whose only content was noise: "### Snapshot" over a file pointer.
        List<String> kept = new java.util.ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            String line = out.get(i).strip();
            int next = i + 1;
            while (next < out.size() && out.get(next).isBlank()) {
                next++;
            }
            boolean emptyHeading = line.startsWith("### ")
                    && (next == out.size() || out.get(next).strip().startsWith("### "));
            if (emptyHeading) {
                continue;
            }
            // `link "Çıkış" [ref=…]:` whose only child was its /url: the colon
            // now announces children that are not there.
            String current = out.get(i);
            if (current.endsWith(":") && current.strip().startsWith("- ")
                    && (next == out.size() || indentOf(out.get(next)) <= indentOf(current))) {
                current = current.substring(0, current.length() - 1);
            }
            kept.add(current);
        }
        return String.join("\n", kept);
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** As BudgetedTool folds the instruction, so the two compare. */
    static String fold(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.forLanguageTag("tr-TR"));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The text inside MCP's content-block array, or the result unchanged.
     *
     * <p><b>Measured 12.11.2026</b> on tape 2628c4fd: this class removed
     * NOTHING from a real page — 10.911 characters in, 10.911 out, then cut
     * at the cap. MCP hands a result over as {@code [{"text":"### Page\n…"}]},
     * a JSON string in which every line break is the two characters
     * {@code \n}. Read that way the whole page is ONE line, no line matches a
     * snapshot node, and nothing is dropped; the code echo is not matched
     * either. The unit tests fed plain text and passed, which is the
     * "measure a page summary on a real walk, never on fixtures alone"
     * lesson arriving a second time. Unwrapped first, the same page goes
     * from 10.413 to 7.906 characters.
     *
     * <p>The model gets the plain text, which reads the same to it and costs
     * fewer tokens than the escaped form. The tape keeps the raw result.
     * Anything that is not that exact shape is passed through untouched.
     */
    static String unwrap(String result) {
        String head = result.stripLeading();
        if (!head.startsWith("[{") || !head.contains("\"text\"")) {
            return result;
        }
        try {
            var root = JSON.readTree(head);
            if (!root.isArray()) {
                return result;
            }
            StringBuilder out = new StringBuilder();
            for (var block : root) {
                var text = block.get("text");
                if (text == null || !text.isTextual()) {
                    return result;
                }
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(text.asText());
            }
            return out.toString();
        } catch (Exception notJson) {
            return result;
        }
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
        // A box with TEXT of its own is a label, not scaffolding: this
        // application prints "Firma Seçimi" as `generic [ref=f3e419]: Firma
        // Seçimi` beside an unnamed combobox, and the label is the only thing
        // that says which box is which. Dropping it went unnoticed only because
        // this class never ran on a real page before unwrap() (12.11.2026).
        String rest = node.group(4).replaceAll("\\[[^]]*]", "").trim();
        if (rest.startsWith(":") && !rest.substring(1).isBlank()) {
            return true;
        }
        String role = node.group(2).toLowerCase(java.util.Locale.ROOT);
        // A scaffold node with no name holds nothing the agent can ask for.
        // Its children keep their own lines; only the box goes.
        return !SCAFFOLD.contains(role);
    }
}
