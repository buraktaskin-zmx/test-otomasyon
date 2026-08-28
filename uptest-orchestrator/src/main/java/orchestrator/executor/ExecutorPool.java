package orchestrator.executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The executors this orchestrator may dispatch to, configured as
 * {@code executors=executor-1:9035,executor-2:9035,executor-3:9035}.
 *
 * Dispatch is try-in-order-until-accepted rather than "ask /nodeStatus, then
 * start": that pair is a race, and a busy executor answers 409 anyway. Losing
 * the race simply means trying the next node.
 */
@Component
public class ExecutorPool {

    private final List<ExecutorClient> executors;

    /** Round-robin starting point, so run 1 and run 2 do not both probe node 1. */
    private final AtomicInteger cursor = new AtomicInteger();

    public ExecutorPool(@Value("${executors}") String executors,
                        @Value("${executorTimeoutMs:5000}") long timeoutMs) {
        this.executors = parse(executors, Duration.ofMillis(timeoutMs));
    }

    private static List<ExecutorClient> parse(String configured, Duration timeout) {
        List<ExecutorClient> clients = new ArrayList<>();
        for (String entry : configured.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String baseUrl = trimmed.startsWith("http") ? trimmed : "http://" + trimmed;
            clients.add(new ExecutorClient(trimmed, baseUrl, timeout));
        }
        if (clients.isEmpty()) {
            throw new IllegalStateException("No executors configured; set the 'executors' property");
        }
        return List.copyOf(clients);
    }

    public List<ExecutorClient> all() {
        return executors;
    }

    /**
     * Hands the run to the first executor that accepts it.
     *
     * @return the executor that took it, or empty when every node was busy or
     *         unreachable — which the caller must surface as 503, never as a
     *         silently queued run.
     */
    public Optional<ExecutorClient> dispatch(UUID runId, String feature) {
        int size = executors.size();
        int start = Math.floorMod(cursor.getAndIncrement(), size);

        for (int i = 0; i < size; i++) {
            ExecutorClient candidate = executors.get((start + i) % size);
            if (candidate.start(runId, feature) == ExecutorClient.Dispatch.ACCEPTED) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public Optional<ExecutorClient> byName(String name) {
        return executors.stream().filter(e -> e.name().equals(name)).findFirst();
    }
}
