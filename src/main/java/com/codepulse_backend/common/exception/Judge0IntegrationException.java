package com.codepulse_backend.common.exception;

public class Judge0IntegrationException extends AppException {

    public Judge0IntegrationException(String message) {
        super("JUDGE0_INTEGRATION_ERROR", message);
    }

    public Judge0IntegrationException(String message, Throwable cause) {
        super("JUDGE0_INTEGRATION_ERROR", message);
        initCause(cause);
    }
}