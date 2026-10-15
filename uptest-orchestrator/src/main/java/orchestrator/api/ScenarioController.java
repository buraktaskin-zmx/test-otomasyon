package orchestrator.api;

import orchestrator.catalog.CatalogModel;
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
    public record ValidateRequest(String feature, String app, String flow, String intent) {
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

    /**
     * The same request plus how many scenarios to produce.
     *
     * {@code count} is boxed so a client that omits it gets the default rather
     * than a batch of zero, and the service clamps it against
     * {@code llm.max-scenarios} — a typed 500 must not occupy the model for an
     * hour.
     */
    public record SuggestManyRequest(String app, String flow, String intent, String cif, String userid,
                                     Integer count) {
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

        // Empty when no screen was named or the catalog will not parse, and
        // that is not an error here: the catalog checks simply do not run.
        CatalogModel catalog = null;
        if (request.app() != null && !request.app().isBlank()
                && request.flow() != null && !request.flow().isBlank()) {
            catalog = catalogService.read(request.app(), request.flow())
                    .flatMap(CatalogModel::parse).orElse(null);
        }

        ValidationResult result = validator.validate(
                request.feature(), vocabulary.get(), catalog, request.intent());
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
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "intent is required"));
        }
        Preparation ready = prepare(request.app(), request.flow(), request.intent(),
                request.cif(), request.userid());
        if (ready.refused()) {
            return ready.refusal();
        }

        return callModel(() -> suggestService.suggest(
                ready.catalogYaml(), request.intent(), ready.vocabulary(), ready.loginStep()));
    }

    /** How many scenarios a batch produces when the client does not say. */
    private static final int DEFAULT_SCENARIOS = 5;

    /**
     * N scenarios for one screen, each exercising a different combination of the
     * values that screen offers.
     *
     * Separate from {@link #suggest} rather than a {@code count} parameter on it,
     * because the two answer different shapes — one draft versus a list of drafts
     * with a plan behind them — and app.js renders them in different places. The
     * guards and the LLM error mapping are shared below, which is what matters:
     * a second copy of them would drift.
     *
     * Slow by nature: the batch is sequential and each scenario is its own
     * generate-validate-repair round, so five take a minute or two. Kept
     * synchronous anyway, like the MCP agent endpoints that already take longer —
     * an asynchronous job needs its own id, store and polling, which is only
     * worth building once someone asks for a batch they can walk away from.
     */
    @PostMapping("/suggest-many")
    public ResponseEntity<?> suggestMany(@RequestBody SuggestManyRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "intent is required"));
        }
        Preparation ready = prepare(request.app(), request.flow(), request.intent(),
                request.cif(), request.userid());
        if (ready.refused()) {
            return ready.refusal();
        }

        int count = request.count() == null ? DEFAULT_SCENARIOS : request.count();
        if (count < 1) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "count en az 1 olmali", "count", count));
        }

        // Credentials are OPTIONAL for one scenario and REQUIRED for a batch,
        // and the asymmetry is the point rather than an oversight.
        //
        // LoginStep.forApp returns null for a blank cif, LoginStep.apply then
        // leaves the draft's own login line alone, and each scenario of a batch
        // is a SEPARATE model call — so N scenarios come back with N
        // independently invented customer numbers. The analyst asked for the
        // same user exercising one screen N ways; they would get N users, and
        // every scenario would need hand-editing before it could run.
        //
        // For a single scenario that is a fair trade: there is one login line
        // and the analyst is looking right at it. Refusing here rather than
        // generating ten drafts nobody can run.
        if (ready.loginStep() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Çoklu üretim için CIF zorunlu",
                    "hint", "Boş bırakılırsa her senaryonun login satırını model kendisi "
                            + "yazar ve " + count + " senaryoda " + count + " farklı müşteri "
                            + "numarası çıkar. Tek senaryo üretiminde boş bırakılabilir."));
        }

        return callModel(() -> {
            SuggestService.Batch batch = suggestService.suggestMany(
                    ready.catalogYaml(), request.intent(), ready.vocabulary(), ready.loginStep(), count);

            // Flattened so each entry is the same shape app.js already knows
            // from /suggest (feature, valid, validation, attempts) plus the
            // variation that distinguishes it. Nesting the Suggestion would
            // make the UI read draft.suggestion.feature for a batch and
            // draft.feature for a single scenario, which is how two render
            // paths start diverging.
            List<Map<String, Object>> scenarios = batch.scenarios().stream()
                    .map(s -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("index", s.index());
                        row.put("label", s.label());
                        row.put("values", s.values());
                        row.put("ticked", s.ticked());
                        row.put("excluded", s.excluded());
                        row.put("feature", s.suggestion().feature());
                        row.put("valid", s.suggestion().valid());
                        row.put("validation", s.suggestion().validation());
                        row.put("attempts", s.suggestion().attempts());
                        row.put("log", s.suggestion().log());
                        return row;
                    })
                    .toList();

            // LinkedHashMap rather than Map.of: the keys stay in the order they
            // are read in, which is the order they are documented in, and the
            // values are deliberately of mixed types that Map.of would have to
            // unify into something unhelpful.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("count", scenarios.size());
            body.put("tokens", batch.tokens());
            body.put("notes", batch.notes());
            body.put("scenarios", scenarios);
            return body;
        });
    }

    /* ======================= shared generation guards ====================== */

    /**
     * Either the three things a generation needs, or the refusal to send back.
     *
     * One of the two halves is always null. A sealed hierarchy would say that in
     * the type system; for two call sites in one class a record and a boolean is
     * the cheaper honest answer.
     */
    private record Preparation(ResponseEntity<?> refusal, Vocabulary vocabulary,
                               String catalogYaml, String loginStep) {

        boolean refused() {
            return refusal != null;
        }
    }

    /**
     * The checks every generation shares: something to generate, a vocabulary to
     * generate against, a screen to generate for, and credentials that can
     * actually become a login line.
     *
     * Order matters and is the order a failure is cheapest to explain in. The
     * vocabulary check in particular refuses rather than guesses — a validator
     * working from a stale step list approves scenarios that then fail as
     * undefined steps.
     */
    private Preparation prepare(String app, String flow, String intent, String cif, String userid) {
        if (intent == null || intent.isBlank()) {
            return new Preparation(
                    ResponseEntity.badRequest().body(Map.of("error", "intent is required")),
                    null, null, null);
        }

        Optional<Vocabulary> vocabulary = vocabularyService.get();
        if (vocabulary.isEmpty()) {
            return new Preparation(
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                            "error", "No executor reachable, so the step vocabulary is unknown",
                            "hint", "Generation is refused rather than guessed: check the executors are up")),
                    null, null, null);
        }

        Optional<String> catalog = catalogService.read(app, flow);
        if (catalog.isEmpty()) {
            return new Preparation(
                    ResponseEntity.badRequest().body(Map.of(
                            "error", "No catalog for " + app + "/" + flow,
                            "available", catalogService.list())),
                    null, null, null);
        }

        // A cif without the userid its application needs cannot become a login
        // line, and guessing one is what this check exists to stop.
        if (cif != null && !cif.isBlank()
                && LoginStep.needsUserid(app)
                && (userid == null || userid.isBlank())) {
            return new Preparation(
                    ResponseEntity.badRequest().body(Map.of("error", "userid is required for " + app)),
                    null, null, null);
        }

        return new Preparation(null, vocabulary.get(), catalog.get(),
                LoginStep.forApp(app, cif, userid));
    }

    /**
     * Runs a model call and turns a failure into 502 rather than a stack trace.
     *
     * The model is a remote service: unreachable, slow or refusing is a normal
     * operational state, not a bug to hide behind a 500 page. The root cause is
     * reported alongside the wrapper because Spring AI's exceptions say almost
     * nothing on their own — the useful line is always several causes down.
     */
    private static ResponseEntity<?> callModel(Supplier<Object> call) {
        try {
            return ResponseEntity.ok(call.get());
        } catch (Exception e) {
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
