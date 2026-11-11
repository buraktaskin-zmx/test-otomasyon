package orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One AI session: everything one press of a button spent on the model.
 *
 * "AI ile çalıştır" is one session however many turns the agent takes; so is
 * one "Senaryo Üret" batch, however many parallel calls it makes. The turns
 * and tool calls hang off it ({@link AiTurnEntity}, {@link AiToolCallEntity});
 * the full prompt and tool texts do NOT live here — they are on disk under
 * {@code runs/llm/<id>/}, because one agent walk is megabytes of page
 * snapshots and this database is also the only copy of the scenario library.
 *
 * Totals are kept on the row, updated after every turn, rather than summed on
 * read: the panel lists sessions and polls a running one every two seconds,
 * and neither should mean scanning every turn of every session.
 *
 * No {@code @Version}: a "Senaryo Üret" batch writes turns from four worker
 * threads at once, all of them into this row. {@code TraceSession} already
 * serialises those updates, and optimistic locking would only turn a correct
 * last write into a failed one.
 *
 * Column names explicit for the reasons {@link TestRunEntity} gives.
 */
@Entity
@Table(name = "AI_SESSION", indexes = {
        @Index(name = "IX_AI_SESSION_STARTED_AT", columnList = "started_at"),
        @Index(name = "IX_AI_SESSION_TAPE_ID", columnList = "tape_id"),
        @Index(name = "IX_AI_SESSION_PARENT_ID", columnList = "parent_id")
})
public class AiSessionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** ai-run, explore, catalog, gherkin, flow-read, flow-plan, flow-suggest, health, other. */
    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    /** RUNNING, DONE, FAILED, UNKNOWN (the orchestrator restarted mid-session). */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** A person-readable name: the scenario's path, or the first words of the instruction. */
    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "app", length = 64)
    private String app;

    @Column(name = "product", length = 64)
    private String product;

    @Column(name = "flow", length = 128)
    private String flow;

    @Column(name = "scenario_path", length = 512)
    private String scenarioPath;

    @Column(name = "cif", length = 64)
    private String cif;

    /** The agent walk's ActionTape, which is how a Gherkin session finds the walk it came from. */
    @Column(name = "tape_id", length = 64)
    private String tapeId;

    /** The session this one was started from: "Gherkin oluştur" → its walk. */
    @Column(name = "parent_id")
    private UUID parentId;

    /** Every model that answered, comma separated: a flow batch can use the vision and the text model. */
    @Column(name = "models", length = 512)
    private String models;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    /** Null when the server never reported a cache figure — which is not the same as zero. */
    @Column(name = "cached_tokens")
    private Long cachedTokens;

    @Column(name = "turn_count", nullable = false)
    private int turnCount;

    @Column(name = "tool_call_count", nullable = false)
    private int toolCallCount;

    @Column(name = "duration_ms")
    private Long durationMs;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error_message", length = Length.LONG32)
    private String errorMessage;

    protected AiSessionEntity() {
    }

    public AiSessionEntity(UUID id, String kind, Instant startedAt) {
        this.id = id;
        this.kind = kind;
        this.startedAt = startedAt;
        this.status = "RUNNING";
    }

    public UUID getId() { return id; }
    public String getKind() { return kind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getApp() { return app; }
    public void setApp(String app) { this.app = app; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public String getFlow() { return flow; }
    public void setFlow(String flow) { this.flow = flow; }
    public String getScenarioPath() { return scenarioPath; }
    public void setScenarioPath(String scenarioPath) { this.scenarioPath = scenarioPath; }
    public String getCif() { return cif; }
    public void setCif(String cif) { this.cif = cif; }
    public String getTapeId() { return tapeId; }
    public void setTapeId(String tapeId) { this.tapeId = tapeId; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }
    public String getModels() { return models; }
    public void setModels(String models) { this.models = models; }
    public long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    public Long getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(Long cachedTokens) { this.cachedTokens = cachedTokens; }
    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }
    public int getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(int toolCallCount) { this.toolCallCount = toolCallCount; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
