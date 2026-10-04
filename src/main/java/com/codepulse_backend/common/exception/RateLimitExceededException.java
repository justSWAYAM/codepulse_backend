package com.codepulse_backend.common.exception;

/** Caller exceeded a request-rate limit. Mapped to 429. */
public class RateLimitExceededException extends AppException {

    public RateLimitExceededException(String message) {
        super("RATE_LIMITED", message);
    }
}
