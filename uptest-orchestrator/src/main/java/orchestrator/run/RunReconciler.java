package orchestrator.run;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Settles rows left unsettled because the orchestrator was killed mid-run.
 *
 * In memory this problem did not exist: a restart lost the whole map. With a
 * table, stopping the orchestrator during a run (the IDE run button, a killed
 * JVM) leaves a row with finished_at IS NULL forever, and every history listing
 * afterwards spends a five-second executor call discovering again that nobody
 * remembers it.
 *
 * TWO LAYERS, because they fail differently:
 *
 *   HERE, at startup: one bulk UPDATE for rows ALREADY past the cutoff when we
 *   booted. Milliseconds, no HTTP, so it cannot delay serving. It exists so
 *   the first history page after a restart is fast instead of paying
 *   refresh-max doomed executor calls.
 *
 *   In RunService.refreshIfUnsettled: rows YOUNGER than the cutoff are left
 *   alone on purpose. The executors may well have SURVIVED the orchestrator
 *   restart and still be running those scenarios, and the right thing is to
 *   ask them — diagnose by measuring, not guessing. Only after the cutoff,
 *   with a measured non-answer, does a row get settled.
 *
 * Status becomes UNKNOWN, not FAILED; see RunStore.settleUnknown for why.
 *
 * @EventListener(ApplicationReadyEvent) rather than an ApplicationRunner:
 * ApplicationRunner beans also execute under @SpringBootTest, and this one
 * WRITES. An event listener needs no interface either, which keeps this module
 * free of its first @Configuration-adjacent type.
 *
 * No @Scheduled and no @EnableScheduling. A long-lived orchestrator settles
 * orphans through the measured path the next time anyone opens the history
 * page, and an orphan only costs anything while someone is looking at it.
 * Running a bulk UPDATE against mostly-zero rows every five minutes is
 * machinery for nothing.
 */
@Component
public class RunReconciler {

    private final RunStore store;
    private final Duration staleAfter;

    public RunReconciler(RunStore store,
                         @Value("${uptest.run.stale-after:30m}") Duration staleAfter) {
        this.store = store;
        this.staleAfter = staleAfter;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void settleOrphans() {
        int settled = store.settleStale(Instant.now().minus(staleAfter), RunService.ORPHAN_NOTE);
        if (settled > 0) {
            // Deliberately on stderr, matching the module's other diagnostics:
            // a nonzero count means someone lost visibility into a run, and it
            // should be greppable in the console with no debug flag.
            System.err.println("[run] reconciled " + settled
                    + " run(s) left unsettled by a restart; marked UNKNOWN");
        }
    }
}
