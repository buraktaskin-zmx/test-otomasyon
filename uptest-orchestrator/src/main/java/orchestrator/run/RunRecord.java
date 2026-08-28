package orchestrator.run;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What the orchestrator remembers about a run. The executor owns the run's
 * artifacts and live status; this records who it was given to and when, so
 * /runs/{id} knows which executor to ask.
 *
 * In memory only: a restart loses run history. A TEST_RUN table is needed
 * before analysts rely on the platform.
 */
public class RunRecord {

    private final UUID id;
    private final String feature;
    private final String executor;
    private final Instant submittedAt;

    private volatile Map<String, Object> lastStatus;

    public RunRecord(UUID id, String feature, String executor, Instant submittedAt) {
        this.id = id;
        this.feature = feature;
        this.executor = executor;
        this.submittedAt = submittedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getFeature() {
        return feature;
    }

    public String getExecutor() {
        return executor;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Map<String, Object> getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(Map<String, Object> lastStatus) {
        this.lastStatus = lastStatus;
    }
}
