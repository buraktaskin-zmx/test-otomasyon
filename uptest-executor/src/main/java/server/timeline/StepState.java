package server.timeline;

/**
 * One Gherkin line as the UI shows it: what it says, where it got to, and how
 * long it took.
 */
public class StepState {

    public enum Status {
        /** Not reached yet. */
        PENDING,
        RUNNING,
        PASSED,
        FAILED,
        /** Not run because an earlier step failed. */
        SKIPPED,
        /** No step definition matched — should have been caught by validation. */
        UNDEFINED
    }

    private final String keyword;
    private final String text;

    private volatile Status status = Status.PENDING;
    private volatile Long durationMs;
    private volatile String error;

    public StepState(String keyword, String text) {
        this.keyword = keyword;
        this.text = text;
    }

    public String getKeyword() {
        return keyword;
    }

    public String getText() {
        return text;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    /** null while the step has not finished — the UI shows a spinner, not a 0. */
    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
