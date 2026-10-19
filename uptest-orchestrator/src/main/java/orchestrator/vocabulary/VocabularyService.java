package orchestrator.vocabulary;

import orchestrator.executor.ExecutorClient;
import orchestrator.executor.ExecutorPool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps the step vocabulary the validator and the LLM prompt both read.
 *
 * Fetched from a live executor rather than copied between modules at build
 * time. That costs one HTTP call and buys the guarantee that matters: the
 * validator describes the steps that are actually deployed, so it can never
 * accept a scenario the executor would then reject as undefined.
 */
@Service
public class VocabularyService {

    private final ExecutorPool pool;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration ttl;
    private final Duration retryAfter;

    private volatile Vocabulary cached;
    private volatile String sourceExecutor;

    /**
     * When the next fetch is allowed. One clock for both cases — the cache
     * going stale and a failed attempt backing off — because both answer the
     * same question: may this call go to the network?
     */
    private volatile Instant nextAttempt = Instant.EPOCH;

    public VocabularyService(ExecutorPool pool,
                             @Value("${uptest.vocabulary.ttl:2m}") Duration ttl,
                             @Value("${uptest.vocabulary.retry-after:15s}") Duration retryAfter) {
        this.pool = pool;
        this.ttl = ttl;
        this.retryAfter = retryAfter;
    }

    /**
     * The vocabulary, re-fetched when the cached copy has gone stale.
     *
     * It used to be cached for the life of the process, and that was wrong in
     * a way that cost us twice: the executor and the orchestrator restart
     * SEPARATELY, so redeploying an executor with a new step left this
     * orchestrator serving the old list — and nothing failed. The validator
     * carried on, the prompt carried on, and the only symptom was a model
     * that never used a step it could not see. An operator has no reason to
     * know they must also poke a second service, and a silent wrong answer is
     * the hardest kind to notice.
     *
     * Per-request fetching would have been the other wrong answer: validation
     * runs on a 700 ms debounce while the analyst types, and each fetch is a
     * blocking call bounded at five seconds.
     *
     * Two rules make the TTL safe:
     *
     *   - A FAILED refresh never discards a working vocabulary. Losing it
     *     would turn a momentary executor restart into "doğrulama yapılamıyor"
     *     on a screen that was fine a second earlier. Stale beats absent:
     *     the step list changes on a deploy, not on a whim.
     *   - A failed refresh backs off. Without it, executors being down would
     *     add a five-second call to every keystroke's validation — the
     *     outage would become a hang.
     */
    public Optional<Vocabulary> get() {
        if (Instant.now().isBefore(nextAttempt)) {
            return Optional.ofNullable(cached);
        }
        return refreshIfDue();
    }

    /**
     * Re-checked inside the lock: two requests can pass the gate together,
     * and the second one should take what the first fetched rather than
     * fetch it again.
     */
    private synchronized Optional<Vocabulary> refreshIfDue() {
        if (Instant.now().isBefore(nextAttempt)) {
            return Optional.ofNullable(cached);
        }
        Optional<Vocabulary> fresh = refresh();

        // The fallback lives HERE and not inside refresh(), because the two
        // callers want opposite things from a failure. An automatic
        // re-fetch wants the show to go on with what it has; the manual
        // endpoint wants to report honestly that it could not reach anyone.
        return fresh.isPresent() ? fresh : Optional.ofNullable(cached);
    }

    /**
     * Fetches now, whatever the clock says — this is also the manual
     * {@code POST /api/v1/vocabulary/refresh}, and an operator pressing it
     * means "now", not "when you were going to anyway".
     */
    public synchronized Optional<Vocabulary> refresh() {
        for (ExecutorClient executor : pool.all()) {
            String json = executor.vocabularyJson();
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                Vocabulary parsed = parse(json);
                if (!parsed.isEmpty()) {
                    cached = parsed;
                    sourceExecutor = executor.name();
                    nextAttempt = Instant.now().plus(ttl);
                    return Optional.of(parsed);
                }
            } catch (RuntimeException e) {
                System.err.println("[vocabulary] unreadable response from " + executor.name() + ": " + e);
            }
        }
        // Nothing answered. Keep whatever we had and come back sooner than a
        // full TTL, but not on the very next call.
        nextAttempt = Instant.now().plus(retryAfter);
        return Optional.empty();
    }

    public String sourceExecutor() {
        return sourceExecutor;
    }

    Vocabulary parse(String json) {
        JsonNode root = mapper.readTree(json);
        List<VocabularyStep> steps = new ArrayList<>();
        for (JsonNode node : root.path("steps")) {
            List<String> parameters = new ArrayList<>();
            for (JsonNode parameter : node.path("parameters")) {
                parameters.add(parameter.asString());
            }
            steps.add(new VocabularyStep(
                    node.path("pattern").asString(),
                    node.path("keyword").asString(),
                    parameters,
                    node.path("className").asString(),
                    node.path("method").asString()
            ));
        }
        return new Vocabulary(steps);
    }
}
