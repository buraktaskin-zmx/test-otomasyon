package orchestrator.api;

import orchestrator.validation.ScenarioValidator;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    public record ValidateRequest(String feature) {
    }

    private final ScenarioValidator validator;
    private final VocabularyService vocabularyService;

    public ScenarioController(ScenarioValidator validator, VocabularyService vocabularyService) {
        this.validator = validator;
        this.vocabularyService = vocabularyService;
    }

    /**
     * Synchronous, no browser, milliseconds. Always 200 with a body saying
     * whether the scenario is valid — an invalid scenario is a normal answer
     * here, not an HTTP error.
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody ValidateRequest request) {
        if (request == null || request.feature() == null || request.feature().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "feature is required"));
        }

        Optional<Vocabulary> vocabulary = vocabularyService.get();
        if (vocabulary.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No executor reachable, so the step vocabulary is unknown",
                    "hint", "Validation is refused rather than guessed: check the executors are up"));
        }

        ValidationResult result = validator.validate(request.feature(), vocabulary.get());
        return ResponseEntity.ok(result);
    }
}
