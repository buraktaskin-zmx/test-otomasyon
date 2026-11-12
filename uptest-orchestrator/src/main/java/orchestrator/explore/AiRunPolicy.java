package orchestrator.explore;

import orchestrator.aitrace.AiTraces;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * What "AI ile çalıştır" adds to a walk, so that it takes as few turns as the
 * scenario needs. Null on every other walk; BudgetedTool asks it after every
 * successful call.
 *
 * <p><b>The page after an action, without a turn for it.</b> The measured walk
 * spent 11 turns on {@code browser_wait_for} and 13 on {@code browser_snapshot}:
 * a click here returns ~160 tokens and no page, so the model asked to wait and
 * then asked to look — two full turns, each resending everything, after every
 * action. Both are things code can do. After an action this waits for the page
 * to settle, takes the snapshot itself and hands it back WITH the action's
 * result. The tools for waiting and searching are not offered on this walk at
 * all (ExploreController's allowlist), so this is the only way the page arrives.
 *
 * <p><b>The budget is a target, not a wall</b> (decided 11.11.2026). Past 80%
 * every result carries a note to finish; nothing is refused and the run is never
 * cut off. Every result rather than once, because CompactingAdvisor cuts old
 * results down: a note given once would be gone two turns later, while repeating
 * it costs a sentence on the one result that is still being sent.
 */
final class AiRunPolicy {

    /** Tools that change the page, after which the agent needs to see it. */
    static final Set<String> ACTING = Set.of(
            "browser_click", "browser_type", "browser_fill_form", "browser_select_option",
            "browser_press_key", "browser_handle_dialog", "set_date",
            // Typing a dropdown's search narrows the list: the agent has to see
            // which options are left before it can pick one.
            "browser_press_sequentially");

    /** How the page appears in a result: MCP's own heading for it. */
    private static final String HAS_PAGE = "Page Snapshot";

    private final ToolCallback snapshot;
    private final Duration settle;
    private final long budget;
    private final LongSupplier spent;
    private final int describeCap;
    private final int pageCap;

    /**
     * @param snapshot the RAW snapshot tool — not a BudgetedTool, so taking the
     *                 page costs no budget and is not mistaken for the model's move
     * @param budget   tokens; 0 or less switches the note off
     */
    AiRunPolicy(ToolCallback snapshot, Duration settle, long budget, int describeCap, int pageCap) {
        this(snapshot, settle, budget, describeCap, pageCap, AiTraces::currentTokens);
    }

    AiRunPolicy(ToolCallback snapshot, Duration settle, long budget, int describeCap, int pageCap,
                LongSupplier spent) {
        this.snapshot = snapshot;
        this.settle = settle;
        this.budget = budget;
        this.describeCap = describeCap;
        this.pageCap = pageCap;
        this.spent = spent;
    }

    long budget() {
        return budget;
    }

    /** The bank's frame around the screen; see PageChrome. Set once the screen is open. */
    private volatile PageChrome chrome = PageChrome.NONE;

    /** The RAW browser_evaluate, for BusyPage's check only; null leaves it off. */
    private volatile ToolCallback probe;

    void busyProbe(ToolCallback evaluate) {
        this.probe = evaluate;
    }

    void chrome(PageChrome learnt) {
        this.chrome = learnt == null ? PageChrome.NONE : learnt;
    }

    PageChrome chrome() {
        return chrome;
    }

    /**
     * Characters kept of each tool's own description (LeanTools).
     *
     * Measured: the definitions were 2,249 tokens of every turn and 40% of a
     * whole run, because the MCP tools arrive with paragraphs of prose. The
     * agent needs the name, a line about what the tool does, and the argument
     * names — this is where "a line" is set.
     */
    int describeCap() {
        return describeCap;
    }

    /**
     * Characters kept of a tool result once it has been summarised.
     *
     * Smaller than the ordinary cap on purpose: the page is already down to
     * its named things (LeanResults), so what is left is dense, and this walk
     * sends one of them on every single turn.
     */
    int pageCap() {
        return pageCap;
    }

    /**
     * What the model gets back from a successful call: the answer, the page as
     * it stands after an action, and the budget note when it is due.
     *
     * @param trim  BudgetedTool's own cap, applied to the page as to any result
     * @param tape  the page is recorded as its own snapshot entry, so the tape
     *              renderer resolves the next click's reference against it
     */
    String after(String tool, String answer, UnaryOperator<String> trim, ActionTape tape) {
        String out = answer == null ? "" : answer;
        if (ACTING.contains(tool) && snapshot != null && !out.contains(HAS_PAGE)) {
            String page = page(tape);
            if (page != null) {
                out = out + "\n\n### Sayfa (işlemden sonra, kendiliğinden alındı)\n" + trim.apply(page);
            }
        }
        return out + budgetNote();
    }

    /** The page after the action has settled, or null when it could not be read. */
    private String page(ActionTape tape) {
        try {
            if (!settle.isZero() && !settle.isNegative()) {
                Thread.sleep(settle.toMillis());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        BusyPage.waitWhileBusy(probe, settle);
        long began = System.nanoTime();
        try {
            String page = snapshot.call("{}");
            long ms = Duration.ofNanos(System.nanoTime() - began).toMillis();
            tape.record("browser_snapshot", "{}", ActionTape.Outcome.OK, page, ms);
            return page;
        } catch (RuntimeException unreadable) {
            // The action itself succeeded; a page that would not read is the
            // agent's to ask for, not a reason to fail the action.
            System.out.println("[uptest] explore: islemden sonra sayfa okunamadi — " + unreadable.getMessage());
            return null;
        }
    }

    /**
     * The budget is spent and the walk must stop.
     *
     * It used to only ADD A NOTE past 80% and refuse nothing (decided
     * 11.11.2026, "uyar ama durdurma"). One run then spent 800.000 tokens
     * without ever logging in: nothing in the system could say no. A limit
     * that is only advice is not a limit, so it stops now — and the one tool
     * still allowed is report_result, so the run ends with a result instead of
     * an exception.
     */
    boolean spentOut() {
        long used = spent.getAsLong();
        return budget > 0 && used >= 0 && used >= budget;
    }

    /** What is left of the budget, for the log and the refusal. */
    long used() {
        return Math.max(0, spent.getAsLong());
    }

    /**
     * Calls that failed on the page as it stands: (tool, arguments) to the
     * error they got.
     *
     * <p><b>Measured 12.11.2026</b>, "Firma Seçimi listesinde arama yapılır":
     * the agent invented two targets, {@code textbox "Seçiniz"} and
     * {@code textbox}, and sent EACH of them five times. Every repeat got the
     * same error, because nothing had changed that could make it pass — and
     * every repeat was a full turn resending the whole conversation. A second
     * identical call on an unchanged page is not a retry, it is the same
     * answer asked for again.
     *
     * <p>"Unchanged" is the load-bearing word: a successful action changes the
     * page, and a call that failed before it may well work after it (a
     * dropdown option is only there once the list is open). So any successful
     * {@link #ACTING} call forgets everything, and only a repeat with nothing
     * in between is refused.
     */
    private final Map<String, String> failures = new ConcurrentHashMap<>();

    /**
     * What one call came to. An error is either thrown (MCP's isError) or
     * returned as text under MCP's own {@code ### Error} heading; both count.
     */
    void outcome(String tool, String input, String result, boolean threw) {
        if (threw || (result != null && result.contains("### Error"))) {
            failures.put(key(tool, input), oneLine(result));
            missed(tool);
        } else if (ACTING.contains(tool)) {
            failures.clear();
            misses.set(0);
        }
    }

    /**
     * Actions in a row that did not happen — failed or refused. A look at the
     * page in between neither counts nor resets: the measured loop was exactly
     * click, refused, snapshot, click, refused, snapshot.
     */
    private final java.util.concurrent.atomic.AtomicInteger misses = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * After this many, the walk is stuck. Measured 12.11.2026, session
     * d88e4b14: 13 clicks, 11 refused and 2 failed, one after another, on a
     * screen that never loaded — 25 turns and 100.901 tokens. The
     * same-call refusal (failedBefore) told the model each time to do
     * something different; it did not, and nothing else said stop.
     */
    static final int STUCK_AFTER = 4;

    void missed(String tool) {
        if (ACTING.contains(tool)) {
            misses.incrementAndGet();
        }
    }

    boolean stuck() {
        return misses.get() >= STUCK_AFTER;
    }

    /** The error this exact call already got on this page, or null. */
    String failedBefore(String tool, String input) {
        return failures.get(key(tool, input));
    }

    private static String key(String tool, String input) {
        return tool + "\n" + (input == null ? "" : input.replaceAll("\\s+", " ").trim());
    }

    private static String oneLine(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    String budgetNote() {
        if (budget <= 0) {
            return "";
        }
        long used = spent.getAsLong();
        if (used < 0 || used < budget * 8 / 10) {
            return "";
        }
        if (used >= budget) {
            return "\n\n[TOKEN BÜTÇESİ AŞILDI: " + used + " / " + budget
                    + ". Hemen report_result çağır; bitiremediğin adımları failed listesine yaz.]";
        }
        return "\n\n[Token bütçesinin %" + (used * 100 / budget) + "'i kullanıldı (" + used + " / " + budget
                + "). Kalan adımları en az çağrıyla bitir ve report_result ile sonuçlan.]";
    }
}
