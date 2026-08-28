package orchestrator.api;

import orchestrator.executor.ExecutorClient;
import orchestrator.executor.ExecutorPool;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
public class VocabularyController {

    private final VocabularyService vocabularyService;
    private final ExecutorPool pool;

    public VocabularyController(VocabularyService vocabularyService, ExecutorPool pool) {
        this.vocabularyService = vocabularyService;
        this.pool = pool;
    }

    /**
     * The step catalog, for the UI's help panel and for building the LLM prompt.
     * Same data the validator uses, so a scenario the UI shows as legal is one
     * the validator accepts.
     */
    @GetMapping("/vocabulary")
    public ResponseEntity<?> vocabulary() {
        Optional<Vocabulary> vocabulary = vocabularyService.get();
        if (vocabulary.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "No executor reachable"));
        }
        return ResponseEntity.ok(Map.of(
                "source", String.valueOf(vocabularyService.sourceExecutor()),
                "stepCount", vocabulary.get().steps().size(),
                "steps", vocabulary.get().steps()));
    }

    /** Re-reads the vocabulary after an executor has been redeployed. */
    @PostMapping("/vocabulary/refresh")
    public ResponseEntity<?> refresh() {
        return vocabularyService.refresh()
                .<ResponseEntity<?>>map(v -> ResponseEntity.ok(Map.of(
                        "source", String.valueOf(vocabularyService.sourceExecutor()),
                        "stepCount", v.steps().size())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "No executor reachable")));
    }

    /** Which executors this orchestrator knows and which are answering. */
    @GetMapping("/executors")
    public List<Map<String, Object>> executors() {
        return pool.all().stream().map(VocabularyController::describe).toList();
    }

    private static Map<String, Object> describe(ExecutorClient executor) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", executor.name());
        view.put("reachable", executor.isReachable());
        return view;
    }
}
