package orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * One step of one run's timeline.
 *
 * BE HONEST ABOUT WHY THIS TABLE EXISTS. The UI does not read these rows — the
 * history listing and the live timeline both render from
 * TEST_RUN.last_status_json. This table exists so the timeline is QUERYABLE:
 * "which step fails most often", "median duration of the login step", per-step
 * reporting. Both are written in one transaction from one payload, so they
 * cannot drift — but one is a verbatim record and the other a parsed
 * projection, and pretending the UI reads this would mislead the next person.
 */
@Entity
@Table(name = "TEST_RUN_STEP",
        uniqueConstraints = @UniqueConstraint(name = "UK_TEST_RUN_STEP",
                columnNames = {"run_id", "step_index"}),
        indexes = @Index(name = "IX_TEST_RUN_STEP_RUN_ID", columnList = "run_id"))
public class TestRunStepEntity {

    /**
     * SEQUENCE, not IDENTITY. IDENTITY forces Hibernate to disable JDBC batch
     * inserts entirely — it must round-trip for each generated key — and these
     * rows are always written as a batch of one run's whole timeline.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "test_run_step_seq")
    @SequenceGenerator(name = "test_run_step_seq", sequenceName = "TEST_RUN_STEP_SEQ",
            allocationSize = 50)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * A plain column, deliberately NOT a @ManyToOne, and there is no
     * @OneToMany on TestRunEntity. Three reasons:
     *
     *  1. NOTHING IN THE UI PATH TRAVERSES IT. The history listing and the
     *     timeline both render from TEST_RUN.last_status_json, so one query
     *     serves the whole page. N+1 on the listing is not avoided here, it is
     *     STRUCTURALLY IMPOSSIBLE: there is no second thing to fetch. No
     *     @EntityGraph, no fetch join, no projection, no DTO query. That is
     *     the entire payoff of this choice.
     *  2. cascade + orphanRemoval is actively wrong for a SNAPSHOT. The step
     *     timeline is rewritten whenever it changes during a run; an
     *     orphanRemoval collection must be LOADED before it can be replaced,
     *     then deletes row by row. Two statements (one bulk delete, one batch
     *     insert) beat eleven.
     *  3. With no association there is nothing lazy on TestRunEntity, which is
     *     what makes it safe for RunStore to hand a managed entity back across
     *     a transaction boundary to describe(). With
     *     spring.jpa.open-in-view=false, a future accidental association then
     *     fails loudly with LazyInitializationException instead of quietly
     *     firing N queries during JSON serialization.
     *
     * THE COST, STATED HONESTLY: no foreign key, so a future retention job
     * that deletes a TEST_RUN must delete its steps FIRST or leave orphans
     * nothing will ever notice. There is no delete path today.
     */
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    /** step_keyword / step_text rather than keyword / text — see
     *  TestRunEntity's note on H2 2.x reserved words. Cheap, no downside. */
    @Column(name = "step_keyword", length = 32)
    private String stepKeyword;

    /** REDACTED, like TEST_RUN.feature: a step's text IS the Gherkin line with
     *  its arguments, so the login step's text carries the credentials. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "step_text", length = Length.LONG32)
    private String stepText;

    /** String for the same reason as TestRunEntity.status: StepState.Status is
     *  the EXECUTOR's enum, arriving here as a wire string. */
    @Column(name = "status", length = 32)
    private String status;

    /**
     * Boxed: null while the step has not finished. StepState documents this
     * and app.js's formatDuration(null) renders "—". A primitive long would
     * print "0 ms" for every step still running.
     */
    @Column(name = "duration_ms")
    private Long durationMs;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error_message", length = Length.LONG32)
    private String errorMessage;

    /** JPA requires it. Never call it. */
    protected TestRunStepEntity() {
    }

    public TestRunStepEntity(UUID runId, int stepIndex, String stepKeyword, String stepText,
                             String status, Long durationMs, String errorMessage) {
        this.runId = runId;
        this.stepIndex = stepIndex;
        this.stepKeyword = stepKeyword;
        this.stepText = stepText;
        this.status = status;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
    }

    public Long getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public String getStepKeyword() {
        return stepKeyword;
    }

    public String getStepText() {
        return stepText;
    }

    public String getStatus() {
        return status;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // No setters and no @Version: these rows are never updated, only deleted
    // and reinserted as a snapshot, and the id is generated so isNew() is
    // already correct.
}
