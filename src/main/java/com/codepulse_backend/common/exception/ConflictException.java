package com.codepulse_backend.common.exception;

/**
 * The request is valid but conflicts with current state (no active session,
 * limit reached, a submission already judging). Mapped to 409 with its own code.
 */
public class ConflictException extends AppException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
