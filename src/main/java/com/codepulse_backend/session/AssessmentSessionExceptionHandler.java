package com.codepulse_backend.session;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
public class AssessmentSessionExceptionHandler {

    @ExceptionHandler(AssessmentSessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSessionNotFound(
            AssessmentSessionNotFoundException ex
    ) {
        ErrorResponse response = new ErrorResponse(
                false,
                ex.getMessage(),
                Instant.now(),
                UUID.randomUUID().toString()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    public record ErrorResponse(
            boolean success,
            String message,
            Instant timestamp,
            String traceId
    ) {}
}