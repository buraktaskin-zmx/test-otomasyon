package server.service;

/**
 * Why a run failed, in a shape that is safe to serialise.
 *
 * Deliberately NOT a {@code Throwable} field: Jackson walks an exception's
 * getters, dumps the whole stack trace into the response and can fail outright
 * on a cause cycle. The orchestrator needs the type and the message; the full
 * trace is in the run log.
 */
public class ErrorDetail {

    private String type;
    private String message;
    private Integer exitCode;

    public static ErrorDetail of(int exitCode) {
        ErrorDetail detail = new ErrorDetail();
        detail.setExitCode(exitCode);
        detail.setMessage("Cucumber exited with code " + exitCode);
        return detail;
    }

    public static ErrorDetail of(Throwable t) {
        ErrorDetail detail = new ErrorDetail();
        detail.setType(t.getClass().getName());
        detail.setMessage(t.getMessage() != null ? t.getMessage() : t.toString());
        return detail;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }
}
