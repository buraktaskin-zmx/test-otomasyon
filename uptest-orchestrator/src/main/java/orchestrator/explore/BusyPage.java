package orchestrator.explore;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A page that is busy loading, and the two things an agent must not do about it.
 *
 * <p><b>Measured 12.11.2026</b>, Mac, ai-run 01604939. After "ASAŞ ALÜMİNYUM
 * SAN." was chosen, Firma Fatura İşlemleri loads the firm's invoices under a
 * full-page overlay, and over the VPN that takes longer than the five seconds
 * MCP gives a click:
 * <pre>
 *   - &lt;div class="overlay-full nonprintable"&gt;&lt;/div&gt; intercepts pointer events
 *   TimeoutError: browserBackend.callTool: Timeout 5000ms exceeded.
 * </pre>
 * The deterministic run passes the same screen because Playwright's own
 * default wait is thirty seconds. The agent instead pressed Escape, then F5
 * twice — the second reload left {@code about:blank} and the run ended with
 * nothing.
 *
 * <p>So: a call refused by an overlay is tried again, in code, until the
 * overlay is gone or about thirty seconds have passed — the click never
 * happened, so trying it again cannot do anything twice. And reloading the
 * page is refused outright: it throws away every step the scenario did.
 */
final class BusyPage {

    /** MCP waits ~5 s per attempt, so six attempts are about Playwright's thirty. */
    static final int ATTEMPTS = 6;

    /** Playwright's own words when something lies over the target. */
    private static final String BLOCKED = "intercepts pointer events";

    /** Keys that reload the page. */
    private static final Set<String> RELOAD = Set.of("f5", "control+r", "ctrl+r", "meta+r",
            "control+f5", "ctrl+f5", "shift+f5", "control+shift+r", "meta+shift+r", "browserrefresh");

    static final String NO_RELOAD = """
            REFUSED: reloading the page is not allowed. It throws away every step
            the scenario has done so far, and on this application it can leave the
            browser on a blank page. If the screen is still loading, the next action
            already waits for it; if it is stuck, call report_result and say so.
            """;

    private BusyPage() {
    }

    /**
     * The call's result, tried again while an overlay is in the way.
     *
     * @param tool the tool's name, for the log
     */
    static String waitOut(String tool, Supplier<String> invoke) {
        for (int attempt = 1; ; attempt++) {
            try {
                String result = invoke.get();
                if (attempt >= ATTEMPTS || !blocked(result)) {
                    return result;
                }
            } catch (BudgetedTool.BrowserClosed closed) {
                throw closed;
            } catch (RuntimeException failed) {
                if (attempt >= ATTEMPTS || !blocked(failed.getMessage())) {
                    throw failed;
                }
            }
            System.out.println("[uptest] explore: " + tool + " — sayfa yukleniyor (overlay), "
                    + attempt + ". deneme, tekrar bekleniyor");
        }
    }

    /**
     * Whether an empty element covers the screen. Generic on purpose — no class
     * name: the element at the centre of the viewport is positioned, spans at
     * least nine tenths of it both ways, and has NO children. That is the
     * shape Playwright printed ({@code <div class="overlay-full
     * nonprintable"></div>}, closed with nothing inside), and it is not the
     * shape of a layout wrapper, which always holds the page.
     */
    static final String PROBE = "() => { const e = document.elementFromPoint(innerWidth / 2, innerHeight / 2);"
            + " if (!e) return 'IDLE'; const s = getComputedStyle(e); const r = e.getBoundingClientRect();"
            + " return (s.position === 'fixed' || s.position === 'absolute') && e.children.length === 0"
            + " && r.width >= innerWidth * 0.9 && r.height >= innerHeight * 0.9 ? 'BUSY' : 'IDLE'; }";

    /** Polls before the page is read anyway. With the 1,5 s settle, thirty seconds. */
    static final int POLLS = 20;

    /**
     * Waits while the page is busy, before it is read and handed to the agent.
     *
     * The page used to be read one settle after an action, overlay or not, so
     * the agent was shown the grid BEFORE the invoices arrived and decided its
     * next move on an empty table. Skipped — never failed — when the
     * evaluate tool is missing or takes no {@code function}: the check is a
     * courtesy, and an MCP version without it must still run.
     *
     * @param evaluate the RAW browser_evaluate; the model never gets it
     */
    static void waitWhileBusy(org.springframework.ai.tool.ToolCallback evaluate, java.time.Duration settle) {
        if (evaluate == null || evaluate.getToolDefinition().inputSchema() == null
                || !evaluate.getToolDefinition().inputSchema().contains("\"function\"")) {
            return;
        }
        long began = System.nanoTime();
        for (int poll = 1; poll <= POLLS; poll++) {
            if (!busy(evaluate)) {
                if (poll > 1) {
                    System.out.println("[uptest] explore: sayfa " + java.time.Duration.ofNanos(System.nanoTime()
                            - began).toMillis() + " ms yuklendi (overlay kalkti)");
                }
                return;
            }
            try {
                Thread.sleep(Math.max(100, settle.toMillis()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println("[uptest] explore: sayfa " + POLLS + " bakista hala yukleniyor — yine de okunuyor");
    }

    static boolean busy(org.springframework.ai.tool.ToolCallback evaluate) {
        try {
            String answer = LeanResults.unwrap(evaluate.call(
                    "{\"function\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(PROBE) + "}"));
            int at = answer == null ? -1 : answer.indexOf("### Result");
            if (at < 0) {
                return false;
            }
            int end = answer.indexOf("###", at + 10);
            String result = end < 0 ? answer.substring(at) : answer.substring(at, end);
            return result.contains("BUSY");
        } catch (Exception unreadable) {
            return false;
        }
    }

    static boolean blocked(String text) {
        return text != null && text.contains(BLOCKED);
    }

    /** The refusal for a key press that reloads the page, or null. */
    static String reload(String tool, String input) {
        if (!"browser_press_key".equals(tool) || input == null) {
            return null;
        }
        String key = TapeEvidence.arg(new TapeRenderer.Call(0, tool, input, "", ""), "key")
                .replace(" ", "").toLowerCase(Locale.ROOT);
        return RELOAD.contains(key) ? NO_RELOAD : null;
    }
}
