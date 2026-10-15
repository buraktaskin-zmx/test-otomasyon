package orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One dispatched run, as the orchestrator knows it.
 *
 * Replaces the ConcurrentHashMap in RunService, which lost the whole history
 * on every restart while the report, log and traces those entries pointed at
 * survived on the executor's disk. The files outlived the record that found
 * them; this table is what closes that.
 *
 * ARTIFACTS STAY ON DISK and keep being served through the executor proxy.
 * Nothing here is a blob: a trace runs to tens of megabytes, and `output` —
 * the entire run.log, which the executor returns inline in its status payload
 * — is stripped before lastStatusJson is written. See RunStore.apply.
 *
 * EVERY COLUMN NAME IS EXPLICIT, for two reasons. H2 2.x reserved a long list
 * of words that 1.x allowed (KEY, VALUE, USER, YEAR, ROW, TOP among them), and
 * a DDL failure from the implicit naming strategy arrives as a syntax error
 * pointing at a generated statement nobody wrote. And ddl-auto=update never
 * renames: a future rename of a Java field would ADD the new column and leave
 * the old one populated and invisible, so naming them here is what keeps a
 * refactor from silently orphaning data.
 *
 * NOT A RECORD, and none of the four entities can be: JPA requires a
 * non-private no-arg constructor and non-final fields.
 */
@Entity
@Table(name = "TEST_RUN", indexes = {
        // The history listing's ORDER BY. A plain btree serves DESC fine.
        @Index(name = "IX_TEST_RUN_SUBMITTED_AT", columnList = "submitted_at"),
        // Highly selective — almost every row is settled — and this is the
        // predicate for "which runs still need an executor call", the only
        // query in the system that costs blocking HTTP per row it returns.
        @Index(name = "IX_TEST_RUN_FINISHED_AT", columnList = "finished_at")
        // NOT indexing `status`: the UI's filter chips (Tümü/Çalışıyor/…)
        // filter CLIENT-side over the array app.js already has, so no query
        // ever puts status in a WHERE clause. Add the index the day a
        // server-side ?status= exists, not before — an unused index is pure
        // write cost on every status poll.
})
public class TestRunEntity {

    /** Minted by RunService with UUID.randomUUID() and handed to the executor,
     *  so it is assigned, never generated. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Boxed Long, and that single character is load-bearing twice over.
     *
     * 1. Because the id is ASSIGNED, Spring Data's
     *    JpaMetamodelEntityInformation.isNew() would return false, save()
     *    would call merge(), and merge issues a pointless SELECT and returns a
     *    DIFFERENT managed instance — leaving the object you hold detached, so
     *    every field set after saving is silently never flushed. A null
     *    version makes isNew() true and save() a straight persist().
     * 2. GET /runs/{id} (polled every 2s) and GET /runs (the history refresh)
     *    both write this row's status from different request threads. Without
     *    a version the loser's write silently overwrites the winner's; with
     *    one the loser gets OptimisticLockingFailureException, which
     *    RunService handles by serving the freshly measured payload.
     *
     * A PRIMITIVE long defeats both: isNew() explicitly falls back to the
     * id-based check when the version attribute is primitive.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    /**
     * Non-null exactly when this run will never be asked about again.
     *
     * The predicate for refreshIfUnsettled, and the thing that stops the
     * orchestrator re-asking an executor about a run it pruned at
     * MAX_RETAINED_RUNS = 200 — which answers 400/E1, not 404.
     */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * String, NOT @Enumerated.
     *
     * The value arrives off the wire as whatever the executor's ResultStatus
     * serialises to, and the orchestrator is not the author of that
     * vocabulary. With a strict enum, the day the executor adds a QUEUED
     * state, Hibernate throws on READ for every row already written — so the
     * history page 500s forever because of a value we ourselves stored, and
     * the only cure is a manual UPDATE. A varchar cannot do that.
     *
     * Today's values: RUNNING, DONE, FAILED, CANCELED (executor) and UNKNOWN
     * (written by this side when a run is reconciled away). Every one has a
     * .pill-* rule in app.css.
     */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "executor", nullable = false, length = 64)
    private String executorName;

    /**
     * The scenario's own title, derived ONCE at insert by ScenarioName.of().
     *
     * Stored rather than re-parsed on every describe() because the text it is
     * parsed from is REDACTED on the way in (see `feature`), and because
     * re-deriving it would make the history listing depend on a parser that
     * may change. Truncated in Java by ScenarioName, never by the column: H2
     * raises "Value too long for column" rather than truncating.
     */
    @Column(name = "scenario_name", nullable = false, length = 512)
    private String scenarioName;

    /** The catalog this was written against; null when no screen was named. */
    @Column(name = "app", length = 64)
    private String app;

    @Column(name = "flow", length = 128)
    private String flow;

    /**
     * The analyst's own words.
     *
     * Kept because it is the only record of WHY a scenario looks the way it
     * does, and because it is already load-bearing for validation: asserting a
     * readonly field's value is legitimate when the ANALYST supplied it.
     *
     * Known limitation, stated rather than papered over: free text is an
     * uncontrolled channel. An analyst who types "12345678 numaralı müşteri
     * için fatura" has persisted a CIF, and no regex can tell that from an
     * invoice number. The mitigation is a UI hint, not a filter that pretends.
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "intent", length = Length.LONG32)
    private String intent;

    /**
     * The Gherkin that ran, WITH ITS CREDENTIALS REDACTED — see Redactor.
     *
     * LoginStep.forApp builds `Given login with cif "<real cif>" and userid
     * "<real userid>"` and LoginStep.apply grafts it into every draft, so the
     * raw feature text carries live bank customer credentials in plaintext.
     * This column is the single place they would have been written down
     * forever with no retention policy, and "we never persist the cif field"
     * would have been true and worthless.
     *
     * A redacted feature cannot be re-run. That is correct, not a limitation:
     * re-running an old scenario against a bank should require re-entering
     * who you are logging in as.
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "feature", nullable = false, length = Length.LONG32)
    private String feature;

    /** Lifted out of lastStatusJson so it is queryable; the UI reads the JSON. */
    @Column(name = "current_step_index")
    private Integer currentStepIndex;

    @Column(name = "step_count")
    private Integer stepCount;

    /**
     * The executor's status payload, verbatim except for two deliberate edits.
     *
     * Verbatim is the point: describe() deserialises this straight back into a
     * LinkedHashMap and puts it under "executorResult", so the JSON app.js
     * receives is byte-identical to what it received when the map was in
     * memory — INCLUDING every key a future executor release might add, which
     * a reconstruct-from-TEST_RUN_STEP approach would silently drop.
     *
     * The two edits, both in RunStore.apply:
     *
     *   steps[].text is REDACTED. A step's text IS the Gherkin line with its
     *   arguments, so the login step's text carries the credentials here too.
     *   Redacting only TEST_RUN.feature would have looked complete and leaked
     *   through this column.
     *
     *   output is NULLED. The executor fills it with the ENTIRE run.log once a
     *   run finishes (CucumberRunner.readLog()). That log is already an
     *   artifact on a persistent volume, served by the proxy and linked in the
     *   UI as "Günlük". Storing it here would put a blob in the database and
     *   multiply the history listing's size by the log size. The key stays,
     *   nulled, so the response shape does not change.
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "last_status_json", length = Length.LONG32)
    private String lastStatusJson;

    /** The executor's error message, or this side's reconciliation note. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error_message", length = Length.LONG32)
    private String errorMessage;

    /** JPA requires it. Never call it. */
    protected TestRunEntity() {
    }

    public TestRunEntity(UUID id, Instant submittedAt, String status, String executorName,
                         String scenarioName, String app, String flow, String intent,
                         String feature) {
        this.id = id;
        this.submittedAt = submittedAt;
        this.status = status;
        this.executorName = executorName;
        this.scenarioName = scenarioName;
        this.app = app;
        this.flow = flow;
        this.intent = intent;
        this.feature = feature;
    }

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getExecutorName() {
        return executorName;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public String getApp() {
        return app;
    }

    public String getFlow() {
        return flow;
    }

    public String getIntent() {
        return intent;
    }

    public String getFeature() {
        return feature;
    }

    public Integer getCurrentStepIndex() {
        return currentStepIndex;
    }

    public Integer getStepCount() {
        return stepCount;
    }

    public String getLastStatusJson() {
        return lastStatusJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // Setters exist ONLY for what a status refresh changes. Everything else is
    // written once at insert, and a missing setter is the cheapest way to say
    // so — ddl-auto=update will not help anyone who mutates history by hand.

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setCurrentStepIndex(Integer currentStepIndex) {
        this.currentStepIndex = currentStepIndex;
    }

    public void setStepCount(Integer stepCount) {
        this.stepCount = stepCount;
    }

    public void setLastStatusJson(String lastStatusJson) {
        this.lastStatusJson = lastStatusJson;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
