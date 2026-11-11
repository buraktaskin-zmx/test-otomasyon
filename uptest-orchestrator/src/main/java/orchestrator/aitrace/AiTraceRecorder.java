package orchestrator.aitrace;

import jakarta.annotation.PreDestroy;
import orchestrator.persistence.AiSessionEntity;
import orchestrator.persistence.AiToolCallEntity;
import orchestrator.persistence.AiTurnEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The trace's Spring side: opens sessions, and is where they write.
 *
 * Installs itself into {@link AiTraces} on construction, which is what turns
 * every static call there from a no-op into a recording.
 *
 * The background worker is ONE thread on purpose: counting tokens is many
 * small requests to a shared server, and the order a session's counts land in
 * does not matter — the panel shows them as they arrive.
 */
@Component
public class AiTraceRecorder implements TraceSink {

    private final AiTraceStore store;
    private final TraceFiles files;
    private final TokenCounter counter;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "ai-trace");
        thread.setDaemon(true);
        return thread;
    });

    public AiTraceRecorder(AiTraceStore store, ObjectMapper mapper,
                           @Value("${uptest.trace.dir:./runs/llm}") String dir,
                           @Value("${uptest.trace.tokenize.enabled:true}") boolean tokenize,
                           @Value("${uptest.trace.tokenize.url:}") String tokenizeUrl,
                           @Value("${spring.ai.openai.base-url:}") String chatBaseUrl,
                           @Value("${spring.ai.openai.chat.options.model:}") String model) {
        this.store = store;
        // Absolute for the reason ActionTape gives: a relative default resolves
        // against whatever directory the orchestrator was started in.
        this.files = new TraceFiles(Path.of(dir), mapper);
        String url = !tokenize ? null
                : tokenizeUrl != null && !tokenizeUrl.isBlank() ? tokenizeUrl
                : TokenCounter.rootOf(chatBaseUrl);
        this.counter = new TokenCounter(url, model, mapper);
        AiTraces.install(this);
        System.out.println("[uptest] ai-trace: AI cagrilari kaydediliyor -> " + files.root()
                + " | token kirilimi: " + (url == null ? "kapali (yalnizca tahmin)" : url + "/tokenize"));
    }

    TraceSession open(String kind, AiTraces.Meta meta, TraceSession enclosing) {
        UUID parent = meta.parentId() != null ? meta.parentId() : enclosing == null ? null : enclosing.id;
        return new TraceSession(UUID.randomUUID(), kind, meta, parent, this);
    }

    UUID sessionOfTape(String tapeId) {
        return store.sessionOfTape(tapeId).orElse(null);
    }

    @PreDestroy
    void stop() {
        AiTraces.uninstall(this);
        worker.shutdown();
    }

    @Override
    public void saveSession(AiSessionEntity session) {
        store.saveSession(session);
    }

    @Override
    public void saveTurn(AiTurnEntity turn) {
        store.saveTurn(turn);
    }

    @Override
    public void saveTool(AiToolCallEntity call) {
        store.saveTool(call);
    }

    @Override
    public void breakdown(UUID turnId, AiTraceStore.Breakdown split) {
        store.breakdown(turnId, split);
    }

    @Override
    public void toolTokens(UUID callId, int tokens, boolean exact) {
        store.toolTokens(callId, tokens, exact);
    }

    @Override
    public TraceFiles files() {
        return files;
    }

    @Override
    public OptionalInt count(String text) {
        return counter.count(text);
    }

    @Override
    public void later(Runnable job) {
        try {
            worker.execute(() -> {
                try {
                    job.run();
                } catch (RuntimeException failed) {
                    System.out.println("[uptest] ai-trace: token sayimi yazilamadi ("
                            + failed.getClass().getSimpleName() + ": " + failed.getMessage() + ")");
                }
            });
        } catch (RuntimeException rejected) {
            // Shutting down: a count that never lands is a blank cell, not an error.
        }
    }
}
