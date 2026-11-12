package orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.Length;

import java.time.Instant;
import java.util.UUID;

/**
 * The steps of a saved scenario's last PASSED "AI ile çalıştır" walk, so the
 * next run can carry them out in code instead of having the agent explore the
 * screen again.
 *
 * One row per scenario, keyed by its id. It holds names and values as the
 * page prints them — never MCP references, which belong to one page load.
 *
 * {@code text_hash} is the SHA-256 of the scenario text the walk was given:
 * when the analyst edits the scenario the hash no longer matches, the row is
 * ignored, and the next passed walk overwrites it. Nothing here decides a
 * verdict; every run checks its expectations again.
 *
 * Not tied to {@code SCENARIO} by a foreign key: a deleted scenario leaves a
 * row nobody looks up, and the library's delete stays the simple operation it
 * is. A new table, so {@code ddl-auto=update} creates it on the next start.
 */
@Entity
@Table(name = "AI_RUN_CACHE")
public class AiRunCacheEntity {

    @Id
    @Column(name = "scenario_id", nullable = false, updatable = false)
    private UUID scenarioId;

    @Column(name = "scenario_path", length = 512)
    private String scenarioPath;

    @Column(name = "text_hash", nullable = false, length = 64)
    private String textHash;

    /** {@code ReplayCalls.ReplayCall} list as JSON: tool calls with page-independent targets. */
    @Column(name = "steps_json", nullable = false, length = Length.LONG32)
    private String stepsJson;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    /** The tape the steps were read from: the walk to open when a replay goes wrong. */
    @Column(name = "source_tape_id", length = 64)
    private String sourceTapeId;

    /** The test user the walk logged in with; a different user may see different rows. */
    @Column(name = "cif", length = 64)
    private String cif;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** The last cached run that ended PASSED. */
    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "hit_count", nullable = false)
    private int hitCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    protected AiRunCacheEntity() {
    }

    public AiRunCacheEntity(UUID scenarioId) {
        this.scenarioId = scenarioId;
    }

    /** Replaces the steps, as after a newly passed walk; the counters start again. */
    public void store(String scenarioPath, String textHash, String stepsJson, int stepCount,
                      String sourceTapeId, String cif, Instant now) {
        this.scenarioPath = scenarioPath;
        this.textHash = textHash;
        this.stepsJson = stepsJson;
        this.stepCount = stepCount;
        this.sourceTapeId = sourceTapeId;
        this.cif = cif;
        this.createdAt = now;
        this.lastValidatedAt = now;
        this.hitCount = 0;
        this.successCount = 0;
        this.failureCount = 0;
    }

    public void used(Instant now) {
        this.lastUsedAt = now;
        this.hitCount++;
    }

    public void passed(Instant now) {
        this.lastValidatedAt = now;
        this.successCount++;
    }

    public void failed() {
        this.failureCount++;
    }

    public UUID getScenarioId() { return scenarioId; }
    public String getScenarioPath() { return scenarioPath; }
    public String getTextHash() { return textHash; }
    public String getStepsJson() { return stepsJson; }
    public int getStepCount() { return stepCount; }
    public String getSourceTapeId() { return sourceTapeId; }
    public String getCif() { return cif; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getLastValidatedAt() { return lastValidatedAt; }
    public int getHitCount() { return hitCount; }
    public int getSuccessCount() { return successCount; }
    public int getFailureCount() { return failureCount; }
}
