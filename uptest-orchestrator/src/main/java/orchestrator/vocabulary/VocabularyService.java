package orchestrator.vocabulary;

import orchestrator.executor.ExecutorClient;
import orchestrator.executor.ExecutorPool;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    private volatile Vocabulary cached;
    private volatile String sourceExecutor;

    public VocabularyService(ExecutorPool pool) {
        this.pool = pool;
    }

    /**
     * The cached vocabulary, refreshing from an executor on first use.
     * Empty when no executor could be reached — callers must treat that as
     * "cannot validate", never as "everything is valid".
     */
    public Optional<Vocabulary> get() {
        Vocabulary local = cached;
        if (local != null) {
            return Optional.of(local);
        }
        return refresh();
    }

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
                    return Optional.of(parsed);
                }
            } catch (RuntimeException e) {
                System.err.println("[vocabulary] unreadable response from " + executor.name() + ": " + e);
            }
        }
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
