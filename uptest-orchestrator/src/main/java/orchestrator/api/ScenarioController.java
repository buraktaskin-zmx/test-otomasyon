package orchestrator.api;

import orchestrator.catalog.CatalogService;
import orchestrator.llm.LoginStep;
import orchestrator.llm.SuggestService;
import orchestrator.validation.ScenarioValidator;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * app + flow name a catalog under catalogs/, intent is free text.
     *
     * cif and userid are separate fields rather than something the analyst
     * mentions in the intent: the login step is built from them, so leaving
     * them in prose meant the model had to find them there and pick an
     * application to match — which it got wrong whenever the intent named no
     * userid. Optional; without a cif the model writes the login itself, as
     * before.
     */
    public record SuggestRequest(String app, String flow, String intent, String cif, String userid) {
    }

    private final ScenarioValidator validator;
    private final VocabularyService vocabularyService;
    private final SuggestService suggestService;
    private final CatalogService catalogService;

    public ScenarioController(ScenarioValidator validator,
                              VocabularyService vocabularyService,
                              SuggestService suggestService,
                              CatalogService catalogService) {
        this.validator = validator;
        this.vocabularyService = vocabularyService;
        this.suggestService = suggestService;
        this.catalogService = catalogService;
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

    /** Which screens the model can be asked about, as "app/flow". */
    @GetMapping("/catalogs")
    public ResponseEntity<?> catalogs() {
        return ResponseEntity.ok(Map.of(
                "root", catalogService.root().toString(),
                "catalogs", catalogService.list()));
    }

    /**
     * Draft a scenario with the LLM, then validate it and let the model repair
     * its own mistakes before an analyst ever sees it.
     *
     * Always 200 when a draft was produced, even an invalid one: the body
     * carries valid=false plus the errors, and a nearly-right scenario in the
     * editor is more useful to an analyst than an error page. Only a missing
     * catalog or an unreachable executor are real failures.
     */
    @PostMapping("/suggest")
    public ResponseEntity<?> suggest(@RequestBody SuggestRequest request) {
        if (request == null || request.intent() == null || request.intent().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "intent is required"));
        }

        Optional<Vocabulary> vocabulary = vocabularyService.get();
        if (vocabulary.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No executor reachable, so the step vocabulary is unknown",
                    "hint", "Generation is refused rather than guessed: check the executors are up"));
        }

        Optional<String> catalog = catalogService.read(request.app(), request.flow());
        if (catalog.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No catalog for " + request.app() + "/" + request.flow(),
                    "available", catalogService.list()));
        }

        // A cif without the userid its application needs cannot become a login
        // line, and guessing one is what this change exists to stop.
        if (request.cif() != null && !request.cif().isBlank()
                && LoginStep.needsUserid(request.app())
                && (request.userid() == null || request.userid().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "userid is required for " + request.app()));
        }
        String loginStep = LoginStep.forApp(request.app(), request.cif(), request.userid());

        try {
            return ResponseEntity.ok(
                    suggestService.suggest(catalog.get(), request.intent(), vocabulary.get(), loginStep));
        } catch (Exception e) {
            // The model is a remote service: unreachable, slow or refusing is a
            // normal operational state, not a bug to hide behind a 500 page.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "LLM call failed",
                    "detail", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "rootCause", root.getClass().getName() + ": " + root.getMessage()));
        }
    }
}
