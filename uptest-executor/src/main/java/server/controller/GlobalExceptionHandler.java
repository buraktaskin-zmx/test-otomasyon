package server.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import server.exception.StatusCodeException;


@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StatusCodeException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(StatusCodeException ex) {
        ErrorResponse error = new ErrorResponse(ex.getErrorCode(),
            ex.getMessage()
        );
        return new ResponseEntity<>(error, HttpStatus.valueOf(ex.getStatusCode()));
    }
}