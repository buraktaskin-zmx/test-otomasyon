package orchestrator.api;

import orchestrator.catalog.CatalogModel;
import orchestrator.catalog.CatalogService;
import orchestrator.llm.LoginStep;
import orchestrator.product.ProductModel;
import orchestrator.product.ProductService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    /**
     * {@code app} + {@code flow} name the catalog to check against and
     * {@code intent} is the analyst's own words; all three optional. Without
     * them this is a grammar check, which is all it ever was — with them it is
     * the same check the generator runs, so the Doğrula button and the run
     * gate stop disagreeing with it.
     */
    public record ValidateRequest(String feature, String app, String flow, String intent,
                                  String product) {
    }

    private final ScenarioValidator validator;
    private final VocabularyService vocabularyService;
    private final CatalogService catalogService;
    private final ProductService productService;

    public ScenarioController(ScenarioValidator validator,
                              VocabularyService vocabularyService,
                              CatalogService catalogService,
                              ProductService productService) {
        this.validator = validator;
        this.vocabularyService = vocabularyService;
        this.catalogService = catalogService;
        this.productService = productService;
    }

    /**
     * The product named by a request, or null.
     *
     * Never guessed from the flow. A product declares its flows, so the
     * mapping could be inverted here — and that would be exactly the silent
     * wrong answer this codebase keeps paying for: a scenario would be
     * validated against a rule book the analyst never chose, and nothing on
     * screen would say so.
     */
    private ProductModel productOf(String app, String product) {
        if (product == null || product.isBlank() || app == null || app.isBlank()) {
            return null;
        }
        return productService.load(app, product).orElse(null);
    }

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

        // Empty when no screen was named or the catalog will not parse, and
        // that is not an error here: the catalog checks simply do not run.
        CatalogModel catalog = null;
        if (request.app() != null && !request.app().isBlank()
                && request.flow() != null && !request.flow().isBlank()) {
            catalog = catalogService.read(request.app(), request.flow())
                    .flatMap(CatalogModel::parse).orElse(null);
        }

        // The product matters here for one reason: it decides which asserted
        // sentences count as catalogued. Leave it out and the Doğrula button
        // would disagree with the generator that just produced the draft —
        // the same "two checks, two answers" problem app/flow was added for.
        ValidationResult result = validator.validate(
                request.feature(), vocabulary.get(), catalog, request.intent(),
                productOf(request.app(), request.product()));
        return ResponseEntity.ok(result);
    }

    /** Which screens the model can be asked about, as "app/flow". */
    @GetMapping("/catalogs")
    public ResponseEntity<?> catalogs() {
        return ResponseEntity.ok(Map.of(
                "root", catalogService.root().toString(),
                "catalogs", catalogService.list()));
    }

    public record SaveCatalogRequest(String app, String flow, String yaml) {
    }

    /**
     * Stores a catalog under catalogs/&lt;app&gt;/&lt;flow&gt;.yaml.
     *
     * The last step of the agent workflow, and the one that was missing: a
     * draft the analyst had to copy out of a textarea by hand went nowhere,
     * so the screen the agent had just walked could not be used to generate
     * anything. Saved here, it lands exactly where {@link #catalogs} looks and
     * the generator offers it immediately.
     *
     * Deliberately NOT validated beyond the name: the agent's own endpoint
     * already reports what is wrong with a draft, and the analyst has read it
     * and possibly edited it before pressing save. Refusing an imperfect
     * catalog here would mean the only way to keep a draft worth keeping is to
     * paste it into a file by hand — which is what this replaces.
     */
    @PostMapping("/catalogs")
    public ResponseEntity<?> saveCatalog(@RequestBody SaveCatalogRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "app, flow ve yaml gerekli"));
        }
        return catalogService.write(body.app(), body.flow(), body.yaml())
                .<ResponseEntity<?>>map(name -> ResponseEntity.ok(Map.of("catalog", name)))
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of(
                        "error", "Gecersiz ad ya da bos icerik. Uygulama ve akis adi yalnizca "
                                + "harf, rakam, nokta, alt tire ve tire icerebilir.")));
    }
}
