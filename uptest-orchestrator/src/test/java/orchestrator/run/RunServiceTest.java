package orchestrator.run;

import orchestrator.catalog.CatalogService;
import orchestrator.executor.ExecutorClient;
import orchestrator.executor.ExecutorPool;
import orchestrator.persistence.TestRunEntity;
import orchestrator.validation.ScenarioValidator;
import orchestrator.validation.ValidationError;
import orchestrator.validation.ValidationResult;
import orchestrator.vocabulary.Vocabulary;
import orchestrator.vocabulary.VocabularyService;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RunService without a Spring context — the payoff of RunStore being a separate
 * bean. Mockito can mock RunStore as a class, so no interface is needed.
 *
 * Deliberately NOT a @SpringBootTest: application.properties points
 * spring.ai.mcp.client.stdio.servers-configuration at .mcp.json, so a full
 * context refresh tries to SPAWN THE MCP STDIO SERVER (an npx process) and
 * either fails for unrelated-looking reasons or hangs until the 120s timeout.
 */
class RunServiceTest {

    private static final String CIF = "DI00002018";
    private static final String USERID = "3014";

    private static final String FEATURE = String.join("\n",
            "Feature: Banko Ödeme Talebi",
            "  Scenario: Kullanıcı ödeme talep eder",
            "    Given login with cif \"" + CIF + "\" and userid \"" + USERID + "\"",
            "    And enter amount \"Ödeme Tutarı\" \"1250,55\"",
            "");

    /** The eight keys app.js reads, in the order describe() must emit them. */
    private static final List<String> UI_CONTRACT = List.of(
            "runId", "name", "executor", "submittedAt",
            "status", "currentStepIndex", "steps", "executorResult");

    private ExecutorPool pool;
    private ScenarioValidator validator;
    private VocabularyService vocabularyService;
    private CatalogService catalogs;
    private RunStore store;
    private RunService service;

    @BeforeEach
    void setUp() {
        pool = mock(ExecutorPool.class);
        validator = mock(ScenarioValidator.class);
        vocabularyService = mock(VocabularyService.class);
        catalogs = mock(CatalogService.class);
        store = mock(RunStore.class);

        service = new RunService(pool, validator, vocabularyService, catalogs, store,
                new ObjectMapper(), 50, 2, Duration.ofMinutes(30));
    }

    // ------------------------------------------------------ the UI contract

    /**
     * WHY THE KEY ORDER AND NOT A LITERAL JSON STRING.
     *
     * The three things that break the browser are a key renamed, a key removed
     * and a key reordered, and the failure mode for all three is a blank column
     * with NOTHING in the console. containsExactly on the key set catches them
     * all.
     *
     * A literal whole-string assertion would additionally couple this test to
     * Jackson's date format — which is configured by Boot, not by the bare
     * mapper a unit test can build — so it would be pinning something that is
     * not the real wire format. Values are asserted separately below.
     */
    @Test
    @DisplayName("describe() emits exactly the eight keys app.js reads, in order")
    void uiContractKeysAndOrder() {
        UUID id = UUID.randomUUID();
        when(store.find(id)).thenReturn(Optional.of(terminalRow(id)));

        Map<String, Object> view = service.status(id).orElseThrow();

        assertThat(view.keySet()).containsExactlyElementsOf(UI_CONTRACT);
    }

    @Test
    @DisplayName("a row with no stored payload reads as the in-memory version did")
    void noStatusYetLooksLikeItAlwaysDid() {
        UUID id = UUID.randomUUID();
        TestRunEntity row = freshRow(id);
        row.setFinishedAt(Instant.now());        // settled, so no executor call
        when(store.find(id)).thenReturn(Optional.of(row));

        Map<String, Object> view = service.status(id).orElseThrow();

        assertThat(view.keySet()).containsExactlyElementsOf(UI_CONTRACT);
        assertThat(view.get("status")).isEqualTo("RUNNING");   // from the column
        assertThat(view.get("currentStepIndex")).isNull();
        assertThat(view.get("steps")).isNull();
        assertThat(view.get("executorResult")).isNull();
        assertThat(view.get("name")).isEqualTo("Kullanıcı ödeme talep eder");
        assertThat(view.get("executor")).isEqualTo("executor-1:9035");
        assertThat(view.get("runId")).isEqualTo(id);
    }

    /**
     * The one case that must still say literally "UNKNOWN": no stored payload
     * AND no status column. describe() has always emitted this for a run no
     * executor ever answered for, and .pill-UNKNOWN in app.css is built for it.
     */
    @Test
    @DisplayName("status falls back to UNKNOWN when there is nothing at all")
    void unknownFallback() {
        UUID id = UUID.randomUUID();
        TestRunEntity row = new TestRunEntity(id, Instant.now(), null, "executor-1:9035",
                "Kullanıcı ödeme talep eder", "ngc", "banko-payment", "intent", FEATURE);
        row.setFinishedAt(Instant.now());
        when(store.find(id)).thenReturn(Optional.of(row));

        assertThat(service.status(id).orElseThrow().get("status")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("mid-run, the timeline is lifted to the top level from the stored JSON")
    void midRunTimelineIsLifted() {
        UUID id = UUID.randomUUID();
        TestRunEntity row = freshRow(id);
        row.setLastStatusJson("{\"status\":\"RUNNING\",\"error\":null,\"output\":null,"
                + "\"steps\":["
                + "{\"keyword\":\"Given\",\"text\":\"login with cif \\\"***\\\"\","
                + "\"status\":\"PASSED\",\"durationMs\":120,\"error\":null},"
                + "{\"keyword\":\"And\",\"text\":\"click \\\"Devam\\\"\","
                + "\"status\":\"RUNNING\",\"durationMs\":null,\"error\":null}],"
                + "\"currentStepIndex\":1}");
        row.setFinishedAt(Instant.now());        // settled, so no executor call
        when(store.find(id)).thenReturn(Optional.of(row));

        Map<String, Object> view = service.status(id).orElseThrow();

        assertThat(view.keySet()).containsExactlyElementsOf(UI_CONTRACT);
        assertThat(view.get("status")).isEqualTo("RUNNING");
        assertThat(view.get("currentStepIndex")).isEqualTo(1);
        assertThat(view.get("steps")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(2);
        assertThat(view.get("executorResult")).isNotNull();
    }

    @Test
    @DisplayName("an unreadable last_status_json degrades instead of failing the request")
    void unreadableJsonDegrades() {
        UUID id = UUID.randomUUID();
        TestRunEntity row = freshRow(id);
        row.setLastStatusJson("{not json at all");
        row.setFinishedAt(Instant.now());
        when(store.find(id)).thenReturn(Optional.of(row));

        Map<String, Object> view = service.status(id).orElseThrow();

        assertThat(view.keySet()).containsExactlyElementsOf(UI_CONTRACT);
        assertThat(view.get("status")).isEqualTo("RUNNING");
        assertThat(view.get("executorResult")).isNull();
    }

    // ------------------------------------------------------------- start()

    /**
     * Pins the decision so a future "let's record everything" cannot land
     * quietly. A rejected run has no executor, no artifacts and no status
     * app.js has a pill for.
     */
    @Test
    @DisplayName("NO_VOCABULARY writes nothing")
    void noVocabularyWritesNothing() {
        when(vocabularyService.get()).thenReturn(Optional.empty());

        RunService.StartOutcome outcome = service.start(FEATURE, "ngc", "banko-payment", "intent");

        assertThat(outcome.rejection()).isEqualTo(RunService.Rejection.NO_VOCABULARY);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("INVALID_SCENARIO writes nothing")
    void invalidScenarioWritesNothing() {
        when(vocabularyService.get()).thenReturn(Optional.of(emptyVocabulary()));
        when(catalogs.read(anyString(), anyString())).thenReturn(Optional.empty());
        when(validator.validate(any(), any(), any(), any())).thenReturn(
                ValidationResult.failed(4, List.of(
                        new ValidationError(3, "bogus step", "tanımsız adım", List.of()))));

        RunService.StartOutcome outcome = service.start(FEATURE, "ngc", "banko-payment", "intent");

        assertThat(outcome.rejection()).isEqualTo(RunService.Rejection.INVALID_SCENARIO);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("NO_FREE_EXECUTOR writes nothing")
    void noFreeExecutorWritesNothing() {
        acceptValidation();
        when(pool.dispatch(any(), anyString())).thenReturn(Optional.empty());

        RunService.StartOutcome outcome = service.start(FEATURE, "ngc", "banko-payment", "intent");

        assertThat(outcome.rejection()).isEqualTo(RunService.Rejection.NO_FREE_EXECUTOR);
        verifyNoInteractions(store);
    }

    /**
     * Belt and braces over RedactorTest, asserted at the call site where it
     * actually matters: this is the one place feature text enters the database.
     */
    @Test
    @DisplayName("the accepted path writes one row, with a REDACTED feature")
    void acceptedPathWritesOneRedactedRow() {
        acceptValidation();
        ExecutorClient client = mock(ExecutorClient.class);
        when(client.name()).thenReturn("executor-2:9035");
        when(pool.dispatch(any(), anyString())).thenReturn(Optional.of(client));

        RunService.StartOutcome outcome = service.start(FEATURE, "ngc", "banko-payment", "fatura testi");

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.executor()).isEqualTo("executor-2:9035");
        verify(store).record(
                eq(outcome.runId()),
                argThat(feature -> !feature.contains(CIF) && !feature.contains(USERID)
                        && feature.contains("login with cif \"***\"")),
                eq("executor-2:9035"),
                eq("Kullanıcı ödeme talep eder"),
                eq("ngc"), eq("banko-payment"), eq("fatura testi"));
    }

    /** The executor still gets the REAL feature — redaction is for the row. */
    @Test
    @DisplayName("the executor is dispatched the unredacted feature, or nothing would log in")
    void executorGetsTheRealFeature() {
        acceptValidation();
        ExecutorClient client = mock(ExecutorClient.class);
        when(client.name()).thenReturn("executor-1:9035");
        when(pool.dispatch(any(), anyString())).thenReturn(Optional.of(client));

        service.start(FEATURE, "ngc", "banko-payment", "intent");

        verify(pool).dispatch(any(), argThat(feature -> feature.contains(CIF)));
    }

    // --------------------------------------------------------------- list()

    @Test
    @DisplayName("a settled row costs zero executor calls")
    void settledRowsAreNeverReasked() {
        when(store.recent(50)).thenReturn(List.of(terminalRow(UUID.randomUUID())));

        service.list(null);

        verifyNoInteractions(pool);
    }

    /**
     * The bound that was missing. In memory the number of unsettled rows was
     * capped by the next restart; with a table nothing caps what a crash
     * leaves behind, and one 5-second executor call each would make this
     * endpoint take 5N seconds.
     */
    @Test
    @DisplayName("list() makes at most refresh-max executor calls, however many rows are open")
    void refreshIsCapped() {
        when(store.recent(50)).thenReturn(List.of(
                freshRow(UUID.randomUUID()), freshRow(UUID.randomUUID()),
                freshRow(UUID.randomUUID()), freshRow(UUID.randomUUID()),
                freshRow(UUID.randomUUID())));
        when(pool.byName(anyString())).thenReturn(Optional.empty());

        List<Map<String, Object>> rows = service.list(null);

        assertThat(rows).hasSize(5);                 // every row is still rendered
        verify(pool, times(2)).byName(anyString());  // refreshMax = 2 in setUp
    }

    @Test
    @DisplayName("an explicit limit is honoured, and clamped rather than trusted")
    void limitIsClampedNotTrusted() {
        when(store.recent(anyInt())).thenReturn(List.of());

        service.list(10);
        service.list(100_000);
        service.list(0);

        verify(store).recent(10);
        verify(store).recent(500);   // MAX_LIST_LIMIT
        verify(store).recent(1);
    }

    @Test
    @DisplayName("one unwritable row does not fail the whole history page")
    void failedRefreshDoesNotFailTheListing() {
        when(store.recent(50)).thenReturn(List.of(freshRow(UUID.randomUUID())));
        ExecutorClient client = mock(ExecutorClient.class);
        when(client.status(any())).thenReturn(new LinkedHashMap<>(Map.of("status", "RUNNING")));
        when(pool.byName(anyString())).thenReturn(Optional.of(client));
        when(store.apply(any(), any())).thenThrow(new OptimisticLockingFailureException("lost"));

        assertThat(service.list(null)).hasSize(1);
    }

    // ------------------------------------------------------------- status()

    @Test
    @DisplayName("an unknown run id returns empty, so RunController still answers 404")
    void unknownIdIsEmpty() {
        UUID id = UUID.randomUUID();
        when(store.find(id)).thenReturn(Optional.empty());

        assertThat(service.status(id)).isEmpty();
        verifyNoInteractions(pool);
    }

    /**
     * A failed audit write must never freeze an analyst's live timeline. Most
     * often this is an optimistic lock failure: the history refresh wrote the
     * same row from another request thread and won.
     */
    @Test
    @DisplayName("a failed persist still serves the freshly measured payload")
    void failedPersistStillServesLiveData() {
        UUID id = UUID.randomUUID();
        when(store.find(id)).thenReturn(Optional.of(freshRow(id)));
        ExecutorClient client = mock(ExecutorClient.class);
        LinkedHashMap<String, Object> live = new LinkedHashMap<>();
        live.put("status", "RUNNING");
        live.put("currentStepIndex", 2);
        when(client.status(id)).thenReturn(live);
        when(pool.byName(anyString())).thenReturn(Optional.of(client));
        when(store.apply(any(), any())).thenThrow(new OptimisticLockingFailureException("lost"));

        Map<String, Object> view = service.status(id).orElseThrow();

        assertThat(view.keySet()).containsExactlyElementsOf(UI_CONTRACT);
        assertThat(view.get("status")).isEqualTo("RUNNING");
        assertThat(view.get("currentStepIndex")).isEqualTo(2);
    }

    @Test
    @DisplayName("a settled run is never re-asked, even by status()")
    void settledStatusSkipsTheExecutor() {
        UUID id = UUID.randomUUID();
        when(store.find(id)).thenReturn(Optional.of(terminalRow(id)));

        service.status(id);

        verify(pool, never()).byName(anyString());
    }

    // ------------------------------------------- artifacts / artifact / stop

    @Test
    @DisplayName("artifacts, artifact and stop all resolve the executor by projection")
    void proxyPathsUseTheProjection() {
        UUID id = UUID.randomUUID();
        when(store.findExecutorName(id)).thenReturn(Optional.empty());

        assertThat(service.artifacts(id)).isEmpty();
        assertThat(service.artifact(id, "report.html")).isEmpty();
        assertThat(service.stop(id)).isFalse();

        verify(store, times(3)).findExecutorName(id);
    }

    // ------------------------------------------------------------- helpers

    private void acceptValidation() {
        when(vocabularyService.get()).thenReturn(Optional.of(emptyVocabulary()));
        when(catalogs.read(anyString(), anyString())).thenReturn(Optional.empty());
        when(validator.validate(any(), any(), any(), any())).thenReturn(ValidationResult.ok(4));
    }

    private static Vocabulary emptyVocabulary() {
        return new Vocabulary(List.of());
    }

    /** Dispatched, nothing heard back yet: finished_at is null. */
    private static TestRunEntity freshRow(UUID id) {
        return new TestRunEntity(id, Instant.now(), "RUNNING", "executor-1:9035",
                "Kullanıcı ödeme talep eder", "ngc", "banko-payment", "intent", FEATURE);
    }

    private static TestRunEntity terminalRow(UUID id) {
        TestRunEntity row = new TestRunEntity(id, Instant.now(), "DONE", "executor-1:9035",
                "Kullanıcı ödeme talep eder", "ngc", "banko-payment", "intent", FEATURE);
        row.setFinishedAt(Instant.now());
        row.setLastStatusJson(
                "{\"status\":\"DONE\",\"output\":null,\"steps\":[],\"currentStepIndex\":-1}");
        return row;
    }
}
