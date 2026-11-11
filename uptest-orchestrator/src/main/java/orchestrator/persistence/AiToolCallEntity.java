package orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One tool call the agent made, as BudgetedTool saw it.
 *
 * {@code turn_seq} is the model turn that asked for it: tool calls run on the
 * same thread between two turns, so the turn that finished last is the one
 * whose answer requested this call.
 *
 * Arguments and result are stored here only as a preview; the whole text —
 * a page snapshot runs to tens of kilobytes — is on disk under its sha, the
 * same masked text the action tape keeps.
 */
@Entity
@Table(name = "AI_TOOL_CALL", indexes = {
        @Index(name = "IX_AI_TOOL_CALL_SESSION", columnList = "session_id, seq")
})
public class AiToolCallEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "turn_seq")
    private Integer turnSeq;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "tool", nullable = false, length = 128)
    private String tool;

    /** OK, REFUSED, ERROR — ActionTape.Outcome. */
    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "args_chars", nullable = false)
    private int argsChars;

    @Column(name = "result_chars", nullable = false)
    private int resultChars;

    @Column(name = "result_tokens")
    private Integer resultTokens;

    @Column(name = "result_exact")
    private Boolean resultExact;

    @Column(name = "args_preview", length = 1000)
    private String argsPreview;

    @Column(name = "result_preview", length = 1000)
    private String resultPreview;

    @Column(name = "args_sha", length = 64)
    private String argsSha;

    @Column(name = "result_sha", length = 64)
    private String resultSha;

    protected AiToolCallEntity() {
    }

    public AiToolCallEntity(UUID id, UUID sessionId, int seq, String tool, String outcome, Instant startedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.seq = seq;
        this.tool = tool;
        this.outcome = outcome;
        this.startedAt = startedAt;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public Integer getTurnSeq() { return turnSeq; }
    public void setTurnSeq(Integer turnSeq) { this.turnSeq = turnSeq; }
    public int getSeq() { return seq; }
    public String getTool() { return tool; }
    public String getOutcome() { return outcome; }
    public Instant getStartedAt() { return startedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public int getArgsChars() { return argsChars; }
    public void setArgsChars(int argsChars) { this.argsChars = argsChars; }
    public int getResultChars() { return resultChars; }
    public void setResultChars(int resultChars) { this.resultChars = resultChars; }
    public Integer getResultTokens() { return resultTokens; }
    public void setResultTokens(Integer resultTokens) { this.resultTokens = resultTokens; }
    public Boolean getResultExact() { return resultExact; }
    public void setResultExact(Boolean resultExact) { this.resultExact = resultExact; }
    public String getArgsPreview() { return argsPreview; }
    public void setArgsPreview(String argsPreview) { this.argsPreview = argsPreview; }
    public String getResultPreview() { return resultPreview; }
    public void setResultPreview(String resultPreview) { this.resultPreview = resultPreview; }
    public String getArgsSha() { return argsSha; }
    public void setArgsSha(String argsSha) { this.argsSha = argsSha; }
    public String getResultSha() { return resultSha; }
    public void setResultSha(String resultSha) { this.resultSha = resultSha; }
}
