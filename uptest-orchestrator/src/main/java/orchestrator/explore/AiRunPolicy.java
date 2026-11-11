package orchestrator.explore;

import orchestrator.aitrace.AiTraces;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.Set;
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
            "browser_press_key", "browser_handle_dialog", "set_date");

    /** How the page appears in a result: MCP's own heading for it. */
    private static final String HAS_PAGE = "Page Snapshot";

    private final ToolCallback snapshot;
    private final Duration settle;
    private final long budget;
    private final LongSupplier spent;

    /**
     * @param snapshot the RAW snapshot tool — not a BudgetedTool, so taking the
     *                 page costs no budget and is not mistaken for the model's move
     * @param budget   tokens; 0 or less switches the note off
     */
    AiRunPolicy(ToolCallback snapshot, Duration settle, long budget) {
        this(snapshot, settle, budget, AiTraces::currentTokens);
    }

    AiRunPolicy(ToolCallback snapshot, Duration settle, long budget, LongSupplier spent) {
        this.snapshot = snapshot;
        this.settle = settle;
        this.budget = budget;
        this.spent = spent;
    }

    long budget() {
        return budget;
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
