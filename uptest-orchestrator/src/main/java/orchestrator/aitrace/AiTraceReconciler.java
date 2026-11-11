package orchestrator.aitrace;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Closes the sessions a previous process left open.
 *
 * A session is RUNNING only while a request thread in THIS process holds it,
 * so at startup every RUNNING row is an orphan: the orchestrator was stopped
 * in the middle of a walk. Its turns are real and stay; only how it ended is
 * unknown — the same UNKNOWN {@code RunReconciler} gives an orphaned test run.
 * Without this the panel would show it "çalışıyor" for ever and keep polling.
 */
@Component
public class AiTraceReconciler {

    static final String ORPHAN_NOTE = "Orchestrator yeniden başladı; oturumun nasıl bittiği bilinmiyor.";

    private final AiTraceStore store;

    public AiTraceReconciler(AiTraceStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void settleOrphans() {
        try {
            int settled = store.settleRunning(Instant.now(), ORPHAN_NOTE);
            if (settled > 0) {
                System.out.println("[uptest] ai-trace: yarim kalan " + settled + " oturum UNKNOWN yapildi");
            }
        } catch (RuntimeException failed) {
            System.out.println("[uptest] ai-trace: yarim oturumlar kapatilamadi (" + failed.getMessage() + ")");
        }
    }
}
