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
 * One model call inside an {@link AiSessionEntity}.
 *
 * For the MCP agent this is one turn of Spring AI's tool loop — the loop
 * itself never surfaces them, an observation handler does. For "Senaryo Üret"
 * it is one of the parallel generation calls.
 *
 * The prompt/completion/cached figures are what the SERVER reported and are
 * exact. The {@code tok_*} columns split the prompt by where it came from and
 * are filled in afterwards, off the request thread: exact when the model
 * server's /tokenize answered ({@code breakdown_exact = true}), otherwise an
 * estimate scaled by this session's own measured characters-per-token. They
 * are null until that has run, and stay null for a vision call, whose images
 * cannot be counted as text. {@code tok_overhead} is the remainder — prompt
 * tokens minus the parts — which is the chat template's own markup when the
 * parts are exact.
 */
@Entity
@Table(name = "AI_TURN", indexes = {
        @Index(name = "IX_AI_TURN_SESSION", columnList = "session_id, seq")
})
public class AiTurnEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "model", length = 256)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "cached_tokens")
    private Long cachedTokens;

    @Column(name = "finish_reason", length = 64)
    private String finishReason;

    /** Messages in this call's prompt. */
    @Column(name = "message_count", nullable = false)
    private int messageCount;

    /** Of those, how many this session had not sent before. */
    @Column(name = "new_message_count", nullable = false)
    private int newMessageCount;

    /** Tool definitions offered on this call. */
    @Column(name = "tool_def_count", nullable = false)
    private int toolDefCount;

    /** Tool calls the model asked for in its answer. */
    @Column(name = "requested_tool_calls", nullable = false)
    private int requestedToolCalls;

    @Column(name = "response_chars", nullable = false)
    private int responseChars;

    @Column(name = "response_preview", length = 1000)
    private String responsePreview;

    @Column(name = "response_sha", length = 64)
    private String responseSha;

    @Column(name = "reasoning_chars", nullable = false)
    private int reasoningChars;

    @Column(name = "reasoning_sha", length = 64)
    private String reasoningSha;

    @Column(name = "tok_system")
    private Integer tokSystem;

    @Column(name = "tok_tool_defs")
    private Integer tokToolDefs;

    @Column(name = "tok_user")
    private Integer tokUser;

    @Column(name = "tok_tool_results")
    private Integer tokToolResults;

    @Column(name = "tok_assistant")
    private Integer tokAssistant;

    @Column(name = "tok_overhead")
    private Integer tokOverhead;

    @Column(name = "breakdown_exact")
    private Boolean breakdownExact;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error_message", length = Length.LONG32)
    private String errorMessage;

    protected AiTurnEntity() {
    }

    public AiTurnEntity(UUID id, UUID sessionId, int seq, Instant startedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.seq = seq;
        this.startedAt = startedAt;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public int getSeq() { return seq; }
    public Instant getStartedAt() { return startedAt; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Long getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(Long cachedTokens) { this.cachedTokens = cachedTokens; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public int getNewMessageCount() { return newMessageCount; }
    public void setNewMessageCount(int newMessageCount) { this.newMessageCount = newMessageCount; }
    public int getToolDefCount() { return toolDefCount; }
    public void setToolDefCount(int toolDefCount) { this.toolDefCount = toolDefCount; }
    public int getRequestedToolCalls() { return requestedToolCalls; }
    public void setRequestedToolCalls(int requestedToolCalls) { this.requestedToolCalls = requestedToolCalls; }
    public int getResponseChars() { return responseChars; }
    public void setResponseChars(int responseChars) { this.responseChars = responseChars; }
    public String getResponsePreview() { return responsePreview; }
    public void setResponsePreview(String responsePreview) { this.responsePreview = responsePreview; }
    public String getResponseSha() { return responseSha; }
    public void setResponseSha(String responseSha) { this.responseSha = responseSha; }
    public int getReasoningChars() { return reasoningChars; }
    public void setReasoningChars(int reasoningChars) { this.reasoningChars = reasoningChars; }
    public String getReasoningSha() { return reasoningSha; }
    public void setReasoningSha(String reasoningSha) { this.reasoningSha = reasoningSha; }
    public Integer getTokSystem() { return tokSystem; }
    public void setTokSystem(Integer tokSystem) { this.tokSystem = tokSystem; }
    public Integer getTokToolDefs() { return tokToolDefs; }
    public void setTokToolDefs(Integer tokToolDefs) { this.tokToolDefs = tokToolDefs; }
    public Integer getTokUser() { return tokUser; }
    public void setTokUser(Integer tokUser) { this.tokUser = tokUser; }
    public Integer getTokToolResults() { return tokToolResults; }
    public void setTokToolResults(Integer tokToolResults) { this.tokToolResults = tokToolResults; }
    public Integer getTokAssistant() { return tokAssistant; }
    public void setTokAssistant(Integer tokAssistant) { this.tokAssistant = tokAssistant; }
    public Integer getTokOverhead() { return tokOverhead; }
    public void setTokOverhead(Integer tokOverhead) { this.tokOverhead = tokOverhead; }
    public Boolean getBreakdownExact() { return breakdownExact; }
    public void setBreakdownExact(Boolean breakdownExact) { this.breakdownExact = breakdownExact; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
