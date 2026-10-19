package orchestrator.run;

import orchestrator.catalog.CatalogModel;
import orchestrator.catalog.CatalogService;
import orchestrator.executor.ExecutorClient;
import orchestrator.executor.ExecutorPool;
import orchestrator.llm.LoginStep;
import orchestrator.persistence.TestRunEntity;
import orchestrator.product.ProductService;
import orchestrator.validation.ScenarioValidator;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    /** Written onto a run the orchestrator was killed in the middle of. */
    static final String ORPHAN_NOTE =
            "Orchestrator restarted while this run was in flight; its outcome was never observed. "
            + "The report and log on the executor's volume are the only evidence.";

    /** Nothing a caller asks for goes past this, whatever it asks for. */
    private static final int MAX_LIST_LIMIT = 500;

    private final ExecutorPool pool;
    private final ScenarioValidator validator;
    private final VocabularyService vocabularyService;
    private final CatalogService catalogs;
    private final ProductService products;
    private final RunStore store;
    private final ObjectMapper mapper;

    private final int defaultListLimit;
    private final int refreshMax;
    private final Duration staleAfter;

    public RunService(ExecutorPool pool, ScenarioValidator validator, VocabularyService vocabularyService,
                      CatalogService catalogs, ProductService products, RunStore store, ObjectMapper mapper,
                      @Value("${uptest.run.list-limit:50}") int defaultListLimit,
                      @Value("${uptest.run.refresh-max:5}") int refreshMax,
                      @Value("${uptest.run.stale-after:30m}") Duration staleAfter) {
        this.pool = pool;
        this.validator = validator;
        this.vocabularyService = vocabularyService;
        this.catalogs = catalogs;
        this.products = products;
        this.store = store;
        this.mapper = mapper;
        this.defaultListLimit = defaultListLimit;
        this.refreshMax = refreshMax;
        this.staleAfter = staleAfter;
    }

    /** Grammar only — for a caller that cannot say which screen this is. */
    public StartOutcome start(String feature) {
        return start(feature, null, null, null);
    }

    private static boolean named(String app, String flow) {
        return app != null && !app.isBlank() && flow != null && !flow.isBlank();
    }

    /**
     * Validates, then dispatches, then records.
     *
     * Validation is repeated here even though the UI already called
     * /scenarios/validate: the UI is not the only possible caller, and burning
     * four minutes of browser time on a typo is exactly what this prevents.
     *
     * It used to be repeated WEAKER, which quietly defeated the point. The
     * catalog-aware checks — an unknown label, a verb that does not fit the
     * type, an option the screen never offered, an assertion of a value nobody
     * supplied — only ran inside SuggestService, at generation time. So a draft
     * the generator had already flagged sailed through here on grammar alone:
     * a model that invented "Bayi Ünvanı" = "Ford Otomobil Sanayi ve Ticaret
     * A.Ş." for a readonly field it never filled got fifteen seconds of browser
     * and a "Locator expected to have value". The gate has to be at least as
     * strict as the generator, or editing the draft launders its mistakes.
     *
     * @param app    with {@code flow}, names the catalog to check against;
     *               null on both means grammar only, exactly as before
     * @param intent the analyst's own words. Load-bearing, not decoration: a
     *               readonly field's value is legitimately asserted when the
     *               ANALYST supplied it, and without the intent that earned
     *               assertion would be rejected here as an invented one.
     */
    public StartOutcome start(String feature, String app, String flow, String intent) {
        return start(feature, app, flow, intent, null);
    }

    /**
     * @param product the product whose rules this scenario was written
     *                against, or null. Load-bearing for the same reason
     *                {@code intent} is: a scenario may legitimately assert an
     *                error message the PRODUCT declares, and without the
     *                product that assertion is rejected here as uncatalogued —
     *                a draft the generator accepted minutes earlier refused at
     *                the gate, which is precisely the disagreement app and
     *                flow were threaded through here to end.
     */
    public StartOutcome start(String feature, String app, String flow, String intent, String product) {
        return start(feature, app, flow, intent, product, null, null);
    }

    /**
     * Runs a scenario under a login the analyst PICKED rather than one typed
     * into the scenario text.
     *
     * The same {@link LoginStep} the generator uses, at the other end of the
     * pipeline and for the same reason: NGC takes a cif AND a userid while NGI
     * takes a customer code alone, the catalog's app key already says which,
     * and a fact that is known should never be a question. It matters more
     * here than there. A registered scenario is written once and run by
     * whoever needs it, so the login is the one part of it that is NOT a
     * property of the scenario — binding a test to one analyst's credentials
     * is how a suite stops being shareable.
     *
     * Order is load-bearing. The login is written in BEFORE validation, so
     * what gets checked is what gets dispatched; applying it afterwards would
     * validate one document and run another, which is the exact disagreement
     * between the editor and this gate that app, flow, intent and product were
     * each threaded through here to end. The recorded text is the rewritten
     * one too, and {@link Redactor} strips the cif out of it on the way to the
     * database — so a picked login reaches the browser and never the column.
     *
     * A blank cif leaves the feature untouched: whatever login it carries is
     * its own, which is what every caller got before this parameter existed.
     */
    public StartOutcome start(String submitted, String app, String flow, String intent, String product,
                              String cif, String userid) {
        String login = LoginStep.forApp(app, cif, userid);
        String feature = login == null ? submitted : LoginStep.apply(submitted, login);

        Optional<Vocabulary> vocabulary = vocabularyService.get();
        if (vocabulary.isEmpty()) {
            // Refusing beats validating against an empty vocabulary, which would
            // reject every step, or skipping validation, which would accept junk.
            return new StartOutcome(null, null, Rejection.NO_VOCABULARY, null);
        }

        // Empty when no screen was named or the catalog will not parse: the
        // catalog checks then do not run, which is the behaviour this had
        // before — never a refusal to start over a missing catalog.
        CatalogModel catalog = named(app, flow)
                ? catalogs.read(app, flow).flatMap(CatalogModel::parse).orElse(null)
                : null;

        // No guard needed: ProductService.load answers empty for a null or
        // blank name, so "no product named" and "no such product" take the
        // same path — nothing to check against, which is this method's
        // long-standing answer to a missing catalog too.
        ValidationResult validation = validator.validate(feature, vocabulary.get(), catalog, intent,
                products.load(app, product).orElse(null));
        if (!validation.valid()) {
            return new StartOutcome(null, null, Rejection.INVALID_SCENARIO, validation);
        }

        UUID runId = UUID.randomUUID();
        Optional<ExecutorClient> executor = pool.dispatch(runId, feature);
        if (executor.isEmpty()) {
            return new StartOutcome(null, null, Rejection.NO_FREE_EXECUTOR, validation);
        }

        /*
         * Recorded AFTER dispatch, exactly where the ConcurrentHashMap was
         * written before it. Inserting first with a DISPATCHING status would
         * mean two writes on the happy path and a row to clean up on the
         * unhappy one — reintroducing the junk rows a rejection deliberately
         * does not create. The cost is a window of microseconds in which a JVM
         * death leaves an executor running a test nobody recorded; that window
         * exists today and nobody has been bitten by it.
         *
         * A REJECTED run is deliberately NOT recorded. It has no executor, no
         * artifacts and no status app.js has a pill for: renderHistory would
         * draw it with .pill-UNKNOWN and two artifact links that 404, and it
         * would push real runs out of the default page. The analyst already got
         * an immediate 422 or 503 and is standing in front of the screen. The
         * questions it could answer are answered elsewhere — "how often is the
         * draft invalid" is SCENARIO_DRAFT.is_valid. When someone genuinely
         * needs "which scenarios were refused last week", the answer is a
         * separate REJECTED_RUN table, not a nullable-everything TEST_RUN.
         *
         * REDACTION HAPPENS HERE, on the way in, so there is one entry point
         * for feature text and no path to the column that bypasses it. The
         * name is derived from the RAW feature: a title is not a login line,
         * and deriving it before redaction keeps the two concerns apart.
         */
        store.record(runId, Redactor.redactFeature(feature), executor.get().name(),
                ScenarioName.of(feature), app, flow, intent, product);

        return new StartOutcome(runId, executor.get().name(), null, validation);
    }

    /**
     * The run as the orchestrator knows it, refreshed from its executor.
     *
     * A run whose executor has been restarted keeps its last known status
     * rather than disappearing — the container restart is the backstop for a
     * hung Playwright call, and analysts still need to see what happened.
     *
     * NO TRANSACTION IS OPEN AROUND THE HTTP CALL, and that is the whole point
     * of this method's shape: ExecutorClient is bounded at executorTimeoutMs
     * (5 s), and holding a database transaction across a five-second network
     * call is how a connection pool of ten becomes a deadlock under three
     * browser tabs. store.find and store.apply each open their own.
     */
    public Optional<Map<String, Object>> status(UUID runId) {
        TestRunEntity row = store.find(runId).orElse(null);
        if (row == null) {
            return Optional.empty();
        }

        if (row.getFinishedAt() == null) {
            Map<String, Object> fresh = pool.byName(row.getExecutorName())
                    .map(executor -> executor.status(runId))
                    .orElse(null);
            if (fresh != null) {
                try {
                    row = store.apply(runId, fresh);
                } catch (DataAccessException e) {
                    /*
                     * Most often an optimistic lock failure (which IS a
                     * DataAccessException): the history refresh wrote this same
                     * row from another request thread and won. Serve the payload
                     * we just measured anyway — a failed audit write must never
                     * freeze an analyst's live timeline, and the next poll two
                     * seconds from now reconciles.
                     */
                    System.err.println("[run] status not persisted for " + runId + ": " + e);
                    return Optional.of(describe(row, fresh));
                }
            }
        }
        return Optional.of(describe(row));
    }

    /** What the run left on disk, asked of the executor that ran it. */
    public Optional<List<Map<String, Object>>> artifacts(UUID runId) {
        return store.findExecutorName(runId)
                .flatMap(pool::byName)
                .map(executor -> executor.artifacts(runId))
                .filter(list -> list != null);
    }

    /** One artifact, proxied from its executor. */
    public Optional<org.springframework.http.ResponseEntity<byte[]>> artifact(UUID runId, String name) {
        return store.findExecutorName(runId)
                .flatMap(pool::byName)
                .map(executor -> executor.artifact(runId, name))
                .filter(response -> response != null);
    }

    public boolean stop(UUID runId) {
        return store.findExecutorName(runId)
                .flatMap(pool::byName)
                .map(executor -> executor.stop(runId))
                .orElse(false);
    }

    /**
     * Newest first, so the UI's history list needs no sorting of its own.
     *
     * Refreshes any run that has not reached a terminal state yet. Without this
     * the history showed UNKNOWN forever for every run the UI had not polled
     * individually — a run started in one browser tab was invisible in another.
     * Terminal results are never re-asked: finished_at being non-null IS the
     * cache, and it is durable now rather than living in a map.
     *
     * The refresh is CAPPED. In memory the number of unsettled rows was
     * bounded by the next restart; with a table, nothing bounds the rows a
     * crash leaves in flight, and one 5-second executor call each would make
     * this endpoint take 5N seconds. Newest-first ordering means the budget is
     * always spent on the runs that are actually live.
     *
     * @param limit optional; null means the configured default
     */
    public List<Map<String, Object>> list(Integer limit) {
        List<TestRunEntity> rows = store.recent(clampLimit(limit));

        // An explicit loop, not the stream's .peek(): peek is not for side
        // effects, and this loop has to COUNT.
        int refreshed = 0;
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (TestRunEntity row : rows) {
            if (row.getFinishedAt() == null && refreshed < refreshMax) {
                refreshed++;
                row = refreshIfUnsettled(row);
            }
            out.add(describe(row));
        }
        return out;
    }

    private int clampLimit(Integer limit) {
        if (limit == null) {
            return defaultListLimit;
        }
        return Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
    }

    /**
     * Asks the executor, and only then decides whether a run is lost.
     *
     * The executor could not tell us for two different reasons, and today
     * ExecutorClient.status() returns null for both (a 400 throws
     * HttpClientErrorException, itself a RestClientException):
     *
     *   unreachable   the node may be restarting — ask again later, which is
     *                 exactly what this did before persistence existed
     *   400 / E1      the executor is UP and has definitively forgotten this
     *                 run, because it prunes at MAX_RETAINED_RUNS = 200
     *
     * Without that distinction the only safe rule is a clock: once a run is
     * older than uptest.run.stale-after (30 m, far beyond the ~4 m a real
     * scenario takes), nobody is ever going to answer for it. Teaching
     * ExecutorClient.status to return "forgotten" instead of null would let an
     * E1 settle on measurement with no clock at all — worth doing, and
     * deliberately not part of this change.
     */
    private TestRunEntity refreshIfUnsettled(TestRunEntity row) {
        Map<String, Object> fresh = pool.byName(row.getExecutorName())
                .map(executor -> executor.status(row.getId()))
                .orElse(null);

        try {
            if (fresh != null) {
                return store.apply(row.getId(), fresh);
            }
            if (row.getSubmittedAt().isBefore(Instant.now().minus(staleAfter))) {
                return store.settleUnknown(row.getId(), ORPHAN_NOTE);
            }
        } catch (DataAccessException e) {
            // One unwritable row must not 500 the whole history page. The row
            // we already hold is still accurate enough to render.
            System.err.println("[run] refresh not persisted for " + row.getId() + ": " + e);
        }
        return row;   // young and unanswered: leave it, try next time
    }

    /**
     * Lifts the step timeline to the top level so the UI reads one shape while
     * polling, whether the run is still going or already finished.
     *
     * THE UI CONTRACT. Eight keys, that order, those types — app.js's
     * renderHistory() and renderTimeline() read exactly these, and a key
     * removed or renamed here is a blank column in the browser with no error
     * anywhere. RunServiceTest pins it against a literal JSON string rather
     * than field by field, because reordering is as breaking as renaming and
     * only a whole-string assertion catches all three.
     *
     * executorResult comes back out of last_status_json as a LinkedHashMap in
     * the key order the executor produced, so every key it sends survives —
     * including ones a future executor release adds, which rebuilding the map
     * from TEST_RUN_STEP rows would have silently dropped.
     *
     * Two documented deltas from the in-memory version, both deliberate:
     * executorResult.output is now always null (the key is still there, app.js
     * never reads it, and the real log is one click away as "Günlük"), and
     * status is RUNNING instead of UNKNOWN in the window between the POST and
     * the first poll — the executor answered 202, so RUNNING is the measured
     * truth.
     */
    private Map<String, Object> describe(TestRunEntity row) {
        return describe(row, parse(row.getLastStatusJson()));
    }

    private static Map<String, Object> describe(TestRunEntity row, Map<String, Object> executorResult) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", row.getId());
        view.put("name", row.getScenarioName());
        view.put("executor", row.getExecutorName());
        view.put("submittedAt", row.getSubmittedAt());
        view.put("status", executorResult != null ? executorResult.get("status") : fallbackStatus(row));
        view.put("currentStepIndex", executorResult != null ? executorResult.get("currentStepIndex") : null);
        view.put("steps", executorResult != null ? executorResult.get("steps") : null);
        view.put("executorResult", executorResult);
        return view;
    }

    /** "UNKNOWN" when there is nothing at all, exactly as before. */
    private static Object fallbackStatus(TestRunEntity row) {
        return row.getStatus() != null ? row.getStatus() : "UNKNOWN";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            // Jackson 3's exceptions are unchecked. A row we cannot read still
            // renders — describe() falls back to the status column — so this
            // degrades instead of failing the request.
            //
            // The SIZE is logged, never the payload: it carries scenario text.
            System.err.println("[run] unreadable last_status_json (" + json.length() + " bytes): " + e);
            return null;
        }
    }
}
