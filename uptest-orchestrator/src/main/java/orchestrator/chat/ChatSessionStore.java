package orchestrator.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live conversations, in memory.
 *
 * Swept on access rather than on a timer, following the decision
 * {@code RunReconciler} already records for this application: no
 * {@code @Scheduled}, no {@code @EnableScheduling}. A timer would be a second
 * thread and a second lifecycle to reason about for the sake of evicting a few
 * strings; doing it when someone opens or continues a chat costs nothing and
 * cannot run while the process is idle, which is exactly when it would not
 * matter anyway.
 *
 * Two bounds, because either alone leaves a hole: time, so an abandoned
 * conversation does not sit on an analyst's intent for ever, and COUNT, so a
 * burst of starts cannot grow the map without limit in the window before any
 * of them expires.
 */
@Component
public class ChatSessionStore {

    private final Map<String, ChatSession> live = new ConcurrentHashMap<>();
    private final Duration idleFor;
    private final int keepAtMost;

    public ChatSessionStore(@Value("${uptest.chat.idle-timeout:30m}") Duration idleFor,
                            @Value("${uptest.chat.max-sessions:50}") int keepAtMost) {
        this.idleFor = idleFor;
        this.keepAtMost = Math.max(1, keepAtMost);
    }

    public ChatSession open(String app, String flow, String cif, String userid) {
        ChatSession session = new ChatSession(UUID.randomUUID().toString(), app, flow, cif, userid);
        live.put(session.id(), session);

        // Swept AFTER the insert, not before. Sweeping first checks a count
        // that is about to change, so the map could finish one over the cap
        // and stay there until the next call — which, on a store whose whole
        // job is to be bounded, is the one thing it must not do. The new
        // session is the youngest, so eviction by age can never take it.
        sweep();
        return session;
    }

    /**
     * Empty for an id that never existed AND for one that has expired — the
     * caller cannot tell them apart and should not try. Both mean the same
     * thing to the analyst: this conversation is gone, start another.
     */
    public Optional<ChatSession> find(String id) {
        sweep();
        ChatSession session = id == null ? null : live.get(id);
        if (session != null) {
            session.touch();
        }
        return Optional.ofNullable(session);
    }

    public void close(String id) {
        if (id != null) {
            live.remove(id);
        }
    }

    public int size() {
        return live.size();
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(idleFor);
        live.values().removeIf(session -> session.touched().isBefore(cutoff));

        // Oldest first, so a burst of abandoned starts cannot push out the
        // conversation someone is in the middle of.
        int excess = live.size() - keepAtMost;
        if (excess <= 0) {
            return;
        }
        List<ChatSession> oldest = live.values().stream()
                .sorted(Comparator.comparing(ChatSession::touched))
                .limit(excess)
                .toList();
        oldest.forEach(session -> live.remove(session.id()));
    }
}
