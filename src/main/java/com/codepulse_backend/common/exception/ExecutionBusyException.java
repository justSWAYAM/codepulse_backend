package com.codepulse_backend.common.exception;

/** Run concurrency limiter timed out waiting for a permit. Mapped to 503. */
public class ExecutionBusyException extends AppException {

    public ExecutionBusyException(String message) {
        super("EXECUTION_BUSY", message);
    }
}
