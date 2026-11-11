package orchestrator.aitrace;

import orchestrator.persistence.AiSessionEntity;
import orchestrator.persistence.AiSessionRepository;
import orchestrator.persistence.AiToolCallEntity;
import orchestrator.persistence.AiToolCallRepository;
import orchestrator.persistence.AiTurnEntity;
import orchestrator.persistence.AiTurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only bean in the trace that touches JPA — {@code RunStore}'s rule for
 * the run path, applied here for the same reason: one place to read when a
 * column is wrong.
 */
@Service
public class AiTraceStore {

    private final AiSessionRepository sessions;
    private final AiTurnRepository turns;
    private final AiToolCallRepository tools;

    public AiTraceStore(AiSessionRepository sessions, AiTurnRepository turns, AiToolCallRepository tools) {
        this.sessions = sessions;
        this.turns = turns;
        this.tools = tools;
    }

    @Transactional
    public void saveSession(AiSessionEntity session) {
        sessions.save(session);
    }

    @Transactional
    public void saveTurn(AiTurnEntity turn) {
        turns.save(turn);
    }

    @Transactional
    public void saveTool(AiToolCallEntity call) {
        tools.save(call);
    }

    /** Fills the prompt split in once it has been counted, off the request thread. */
    @Transactional
    public void breakdown(UUID turnId, Breakdown split) {
        turns.findById(turnId).ifPresent(turn -> {
            turn.setTokSystem(split.system());
            turn.setTokToolDefs(split.toolDefs());
            turn.setTokUser(split.user());
            turn.setTokToolResults(split.toolResults());
            turn.setTokAssistant(split.assistant());
            turn.setTokOverhead(split.overhead());
            turn.setBreakdownExact(split.exact());
        });
    }

    @Transactional
    public void toolTokens(UUID callId, int tokens, boolean exact) {
        tools.findById(callId).ifPresent(call -> {
            call.setResultTokens(tokens);
            call.setResultExact(exact);
        });
    }

    @Transactional
    public int settleRunning(Instant cutoff, String note) {
        return sessions.settleRunning(cutoff, Instant.now(), note);
    }

    @Transactional(readOnly = true)
    public List<AiSessionEntity> sessionsSince(Instant since) {
        return sessions.findByStartedAtGreaterThanEqualOrderByStartedAtDesc(since);
    }

    @Transactional(readOnly = true)
    public Optional<AiSessionEntity> session(UUID id) {
        return sessions.findById(id);
    }

    @Transactional(readOnly = true)
    public List<AiSessionEntity> children(UUID parentId) {
        return sessions.findByParentIdOrderByStartedAtAsc(parentId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> sessionOfTape(String tapeId) {
        if (tapeId == null || tapeId.isBlank()) {
            return Optional.empty();
        }
        return sessions.findByTapeIdOrderByStartedAtDesc(tapeId).stream()
                .filter(session -> session.getParentId() == null)
                .map(AiSessionEntity::getId)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<AiTurnEntity> turns(UUID sessionId) {
        return turns.findBySessionIdOrderBySeqAsc(sessionId);
    }

    @Transactional(readOnly = true)
    public List<AiToolCallEntity> toolCalls(UUID sessionId) {
        return tools.findBySessionIdOrderBySeqAsc(sessionId);
    }

    /**
     * A prompt split by source. Every part is null when it could not be
     * counted; {@code overhead} is prompt minus the parts.
     */
    public record Breakdown(Integer system, Integer toolDefs, Integer user, Integer toolResults,
                            Integer assistant, Integer overhead, boolean exact) {
    }
}
