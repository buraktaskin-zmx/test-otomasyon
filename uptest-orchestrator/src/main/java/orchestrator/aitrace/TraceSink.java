package orchestrator.aitrace;

import orchestrator.persistence.AiSessionEntity;
import orchestrator.persistence.AiToolCallEntity;
import orchestrator.persistence.AiTurnEntity;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * Where a {@link TraceSession} writes: the database, the disk, the tokenizer
 * and a background worker.
 *
 * An interface so a session can be exercised without Spring, a database or a
 * model server — {@link AiTraceRecorder} is the real one.
 */
interface TraceSink {

    void saveSession(AiSessionEntity session);

    void saveTurn(AiTurnEntity turn);

    void saveTool(AiToolCallEntity call);

    void breakdown(UUID turnId, AiTraceStore.Breakdown split);

    void toolTokens(UUID callId, int tokens, boolean exact);

    TraceFiles files();

    /** The tokenizer's count, or empty when it cannot be asked. */
    OptionalInt count(String text);

    /** Runs a job off the calling thread; a failing job is logged, never rethrown. */
    void later(Runnable job);
}
