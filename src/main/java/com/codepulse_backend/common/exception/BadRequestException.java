package com.codepulse_backend.common.exception;

/** The request itself is unusable (malformed upload, bad parameter). Mapped to 400. */
public class BadRequestException extends AppException {

    public BadRequestException(String message) {
        super("BAD_REQUEST", message);
    }
}
