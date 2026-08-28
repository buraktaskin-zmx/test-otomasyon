package server.exception;

public class StatusCodeException extends RuntimeException{

    private ErrorCodes errorCode;

    private Integer statusCode;

    // Passed to super as well, so logs and stack traces carry the message
    // instead of the null they showed while this field merely shadowed it.
    public StatusCodeException(ErrorCodes errorCode, Integer statusCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.statusCode = statusCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    // getMessage() is inherited from Throwable now that the message reaches super.

    public ErrorCodes getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(ErrorCodes errorCode) {
        this.errorCode = errorCode;
    }
}
 