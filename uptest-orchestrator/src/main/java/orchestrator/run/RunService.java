package orchestrator.run;

import orchestrator.executor.ExecutorClient;
import orchestrator.executor.ExecutorPool;
import orchestrator.validation.ScenarioValidator;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RunService {

    /** Why a run could not be started, so the API can pick the right status. */
    public enum Rejection {
        INVALID_SCENARIO,
        NO_VOCABULARY,
        NO_FREE_EXECUTOR
    }

    public record StartOutcome(UUID runId, String executor, Rejection rejection, ValidationResult validation) {
        public boolean accepted() {
            return rejection == null;
        }
    }

    private final ExecutorPool pool;
    private final ScenarioValidator validator;
    private final VocabularyService vocabularyService;
    private final ConcurrentHashMap<UUID, RunRecord> runs = new ConcurrentHashMap<>();

    public RunService(ExecutorPool pool, ScenarioValidator validator, VocabularyService vocabularyService) {
        this.pool = pool;
        this.validator = validator;
        this.vocabularyService = vocabularyService;
    }

    /**
     * Validates, then dispatches.
     *
     * Validation is repeated here even though the UI already called
     * /scenarios/validate: the UI is not the only possible caller, and burning
     * four minutes of browser time on a typo is exactly what this prevents.
     */
    public StartOutcome start(String feature) {
        Optional<Vocabulary> vocabulary = vocabularyService.get();
        if (vocabulary.isEmpty()) {
            // Refusing beats validating against an empty vocabulary, which would
            // reject every step, or skipping validation, which would accept junk.
            return new StartOutcome(null, null, Rejection.NO_VOCABULARY, null);
        }

        ValidationResult validation = validator.validate(feature, vocabulary.get());
        if (!validation.valid()) {
            return new StartOutcome(null, null, Rejection.INVALID_SCENARIO, validation);
        }

        UUID runId = UUID.randomUUID();
        Optional<ExecutorClient> executor = pool.dispatch(runId, feature);
        if (executor.isEmpty()) {
            return new StartOutcome(null, null, Rejection.NO_FREE_EXECUTOR, validation);
        }

        runs.put(runId, new RunRecord(runId, feature, executor.get().name(), Instant.now()));
        return new StartOutcome(runId, executor.get().name(), null, validation);
    }

    /**
     * The run as the orchestrator knows it, refreshed from its executor.
     *
     * A run whose executor has been restarted keeps its last known status
     * rather than disappearing — the container restart is the backstop for a
     * hung Playwright call, and analysts still need to see what happened.
     */
    public Optional<Map<String, Object>> status(UUID runId) {
        RunRecord record = runs.get(runId);
        if (record == null) {
            return Optional.empty();
        }

        pool.byName(record.getExecutor())
                .map(executor -> executor.status(runId))
                .filter(status -> status != null)
                .ifPresent(record::setLastStatus);

        return Optional.of(describe(record));
    }

    /** What the run left on disk, asked of the executor that ran it. */
    public Optional<List<Map<String, Object>>> artifacts(UUID runId) {
        RunRecord record = runs.get(runId);
        if (record == null) {
            return Optional.empty();
        }
        return pool.byName(record.getExecutor())
                .map(executor -> executor.artifacts(runId))
                .filter(list -> list != null);
    }

    /** One artifact, proxied from its executor. */
    public Optional<org.springframework.http.ResponseEntity<byte[]>> artifact(UUID runId, String name) {
        RunRecord record = runs.get(runId);
        if (record == null) {
            return Optional.empty();
        }
        return pool.byName(record.getExecutor())
                .map(executor -> executor.artifact(runId, name))
                .filter(response -> response != null);
    }

    public boolean stop(UUID runId) {
        RunRecord record = runs.get(runId);
        if (record == null) {
            return false;
        }
        return pool.byName(record.getExecutor()).map(executor -> executor.stop(runId)).orElse(false);
    }

    /**
     * Newest first, so the UI's history list needs no sorting of its own.
     *
     * Refreshes any run that has not reached a terminal state yet. Without this
     * the history showed UNKNOWN forever for every run the UI had not polled
     * individually — a run started in one browser tab was invisible in another.
     * Terminal results are cached: DONE/FAILED/CANCELED never change, so a full
     * history listing costs at most one call per still-open run.
     */
    public List<Map<String, Object>> list() {
        return runs.values().stream()
                .sorted(Comparator.comparing(RunRecord::getSubmittedAt).reversed())
                .peek(this::refreshIfUnsettled)
                .map(RunService::describe)
                .toList();
    }

    private static final Set<String> TERMINAL = Set.of("DONE", "FAILED", "CANCELED");

    private void refreshIfUnsettled(RunRecord record) {
        Map<String, Object> last = record.getLastStatus();
        if (last != null && TERMINAL.contains(String.valueOf(last.get("status")))) {
            return;
        }
        pool.byName(record.getExecutor())
                .map(executor -> executor.status(record.getId()))
                .filter(status -> status != null)
                .ifPresent(record::setLastStatus);
    }

    /**
     * Lifts the step timeline to the top level so the UI reads one shape while
     * polling, whether the run is still going or already finished.
     */
    private static Map<String, Object> describe(RunRecord record) {
        Map<String, Object> executorResult = record.getLastStatus();

        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("runId", record.getId());
        view.put("name", scenarioName(record.getFeature()));
        view.put("executor", record.getExecutor());
        view.put("submittedAt", record.getSubmittedAt());
        view.put("status", executorResult != null ? executorResult.get("status") : "UNKNOWN");
        view.put("currentStepIndex", executorResult != null ? executorResult.get("currentStepIndex") : null);
        view.put("steps", executorResult != null ? executorResult.get("steps") : null);
        view.put("executorResult", executorResult);
        return view;
    }

    /**
     * The scenario's own title, so the history list reads like the analyst's
     * work rather than a column of UUIDs. Falls back to the feature title, then
     * to a placeholder.
     */
    private static String scenarioName(String feature) {
        if (feature == null) {
            return "(isimsiz)";
        }
        String featureTitle = null;
        for (String raw : feature.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("Scenario:") || line.startsWith("Senaryo:")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
            if (featureTitle == null && (line.startsWith("Feature:") || line.startsWith("Özellik:"))) {
                featureTitle = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return featureTitle != null ? featureTitle : "(isimsiz)";
    }
}
