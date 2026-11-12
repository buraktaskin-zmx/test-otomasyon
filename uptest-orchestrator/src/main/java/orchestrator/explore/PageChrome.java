package orchestrator.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of every page that are not the screen: the header, the main menu,
 * the footer. Learnt once, from the page the screen opened on, and left out of
 * every page the agent is shown afterwards.
 *
 * <p><b>Measured 12.11.2026</b> (tape 2628c4fd): of the 10.413 characters of
 * Firma Fatura İşlemleri with a row picked, about 3.400 were the bank's own
 * frame — Çıkış, Ayarlarım, the user's name, HESAPLAR … SİGORTA, the last
 * login time, the copyright line — identical on every page and sent again
 * after every click. On "AI ile çalıştır" the screen is opened BY CODE
 * (AiRunPrelude) and the agent is told not to go to the menu, so it has no use
 * for any of it.
 *
 * <p><b>Found by structure, not by a list of words.</b> The screen is the
 * subtree under its own heading ({@code heading "Firma Fatura İşlemleri"}),
 * grown upwards for as long as it does not swallow the main-menu link. What
 * sits BESIDE that path — siblings of the screen and of each of its ancestors —
 * is the frame. Those subtrees are remembered by their references, which this
 * application keeps from page to page.
 *
 * <p><b>Why this does not repeat 40a2a7f</b>, which shrank the page cleverly
 * and hid the menu link the agent's next move needed:
 * <ul>
 *   <li>only the subtrees that were there when the screen OPENED are left
 *       out. A dialog, a Select2 list or its search box appears later, under
 *       references the frame never had, and is shown in full;</li>
 *   <li>a frame subtree containing anything the SCENARIO names is kept;</li>
 *   <li>it is only learnt when code opened the screen. When the agent has to
 *       find the screen itself, nothing is left out;</li>
 *   <li>a page reloaded from scratch has new references, matches nothing, and
 *       comes through whole;</li>
 *   <li>what was left out is said, in one line, where it was.</li>
 * </ul>
 */
final class PageChrome {

    /** Leaves everything in. */
    static final PageChrome NONE = new PageChrome(Set.of());

    static final String NOTE = "- (üst bilgi, ana menü ve alt bilgi gösterilmedi: ekran zaten açık)";

    private static final Pattern REF = Pattern.compile("\\[ref=([A-Za-z0-9]+)]");

    private final Set<String> refs;

    private PageChrome(Set<String> refs) {
        this.refs = refs;
    }

    /** How many subtrees are left out; for the log. */
    int size() {
        return refs.size();
    }

    /**
     * The frame of an "AI ile çalıştır" walk: learnt only when code opened
     * the screen, i.e. when the agent is told not to use the menu.
     */
    static PageChrome forRun(AiRunPrelude.Outcome opened, String mainMenu, String screen, String scenario) {
        PageChrome chrome = opened.reached() == AiRunPrelude.Reached.ON_SCREEN
                ? learn(opened.page(), mainMenu, screen, AiRunPrelude.withoutGoTo(scenario == null ? "" : scenario))
                : NONE;
        System.out.println("[uptest] ai-run: cerceve " + chrome.size()
                + " alt agac (ust bilgi/menu/alt bilgi) gosterilmeyecek");
        return chrome;
    }

    /**
     * The prelude's outcome with its page as the agent should first see it.
     *
     * The opening page used to go into the first user message RAW — MCP's
     * JSON, line breaks escaped, up to 12.000 characters — while every later
     * page was summarised. Measured on tape 2628c4fd: 7.070 characters raw,
     * 1.734 summarised and without the frame.
     */
    AiRunPrelude.Outcome shown(AiRunPrelude.Outcome opened, String scenario, int pageCap) {
        if (opened.page() == null) {
            return opened;
        }
        String page = LeanResults.summarise(strip(LeanResults.unwrap(opened.page())), pageCap,
                LeanResults.fold(scenario));
        return new AiRunPrelude.Outcome(opened.reached(), page, opened.note());
    }

    /**
     * @param page     the page the screen opened on, as MCP returned it
     * @param mainMenu the menu the prelude opened, e.g. NAKİT YÖNETİMİ
     * @param screen   the screen's own name, e.g. Firma Fatura İşlemleri
     * @param scenario the analyst's scenario text: a frame subtree naming
     *                 anything in it is kept
     * @return {@link #NONE} whenever the shape is not the one expected
     */
    static PageChrome learn(String page, String mainMenu, String screen, String scenario) {
        if (page == null || blank(mainMenu) || blank(screen)) {
            return NONE;
        }
        AriaSnapshot tree = AriaSnapshot.of(page);
        List<AriaSnapshot.Node> nodes = tree.nodes();
        int heading = -1;
        int menu = -1;
        for (int i = 0; i < nodes.size(); i++) {
            AriaSnapshot.Node node = nodes.get(i);
            if (heading < 0 && "heading".equals(node.role()) && same(node.name(), screen)) {
                heading = i;
            }
            if (menu < 0 && "link".equals(node.role()) && same(node.name(), mainMenu)) {
                menu = i;
            }
        }
        if (heading < 0 || menu < 0) {
            return NONE;
        }

        // The screen: the highest ancestor of its heading that does not
        // contain the main menu.
        List<Integer> path = new ArrayList<>();
        for (int at = heading; at >= 0; at = nodes.get(at).parent()) {
            path.add(0, at);
        }
        int screenAt = -1;
        for (int k = path.size() - 1; k >= 0; k--) {
            if (contains(nodes, path.get(k), menu)) {
                break;
            }
            screenAt = k;
        }
        if (screenAt <= 0) {
            // The heading's own branch holds the menu, or the screen is the
            // whole page: nothing can be called the frame safely.
            return NONE;
        }

        String wanted = TapeRenderer.fold(scenario == null ? "" : scenario);
        Set<String> onPath = new HashSet<>();
        for (int k = 0; k <= screenAt; k++) {
            onPath.add(nodes.get(path.get(k)).ref());
        }
        Set<String> frame = new HashSet<>();
        for (int k = 0; k < screenAt; k++) {
            int parent = path.get(k);
            for (AriaSnapshot.Node child : tree.childrenOf(nodes.get(parent).ref())) {
                if (onPath.contains(child.ref())) {
                    continue;
                }
                if (!named(tree, nodes, child, wanted, mainMenu, screen)) {
                    frame.add(child.ref());
                }
            }
        }
        return frame.isEmpty() ? NONE : new PageChrome(frame);
    }

    /**
     * The page without the frame. Takes the PLAIN text (LeanResults.unwrap):
     * the frame is cut by line and indent.
     */
    String strip(String text) {
        if (refs.isEmpty() || text == null) {
            return text;
        }
        List<String> out = new ArrayList<>();
        boolean noted = false;
        boolean changed = false;
        int skipDeeperThan = -1;
        for (String line : text.split("\n", -1)) {
            int indent = indentOf(line);
            if (skipDeeperThan >= 0) {
                if (indent > skipDeeperThan && !line.isBlank()) {
                    continue;
                }
                skipDeeperThan = -1;
            }
            Matcher ref = REF.matcher(line);
            if (ref.find() && refs.contains(ref.group(1))) {
                skipDeeperThan = indent;
                changed = true;
                if (!noted) {
                    noted = true;
                    out.add(" ".repeat(indent) + NOTE);
                }
                continue;
            }
            out.add(line);
        }
        return changed ? String.join("\n", out) : text;
    }

    private static boolean contains(List<AriaSnapshot.Node> nodes, int ancestor, int node) {
        for (int at = node; at >= 0; at = nodes.get(at).parent()) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Whether anything in this subtree is named in the scenario. */
    private static boolean named(AriaSnapshot tree, List<AriaSnapshot.Node> nodes, AriaSnapshot.Node root,
                                 String wanted, String mainMenu, String screen) {
        if (wanted.isEmpty()) {
            return false;
        }
        int start = nodes.indexOf(root);
        for (int i = start; i < nodes.size(); i++) {
            if (i > start && nodes.get(i).indent() <= root.indent()) {
                break;
            }
            String caption = nodes.get(i).caption();
            if (caption.length() < 3 || same(caption, mainMenu) || same(caption, screen)) {
                continue;
            }
            if (wanted.contains(TapeRenderer.fold(caption))) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(String a, String b) {
        return !blank(a) && !blank(b) && TapeRenderer.fold(a).equals(TapeRenderer.fold(b));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }
}
