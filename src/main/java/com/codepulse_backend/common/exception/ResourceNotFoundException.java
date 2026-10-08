package com.codepulse_backend.common.exception;

public class ResourceNotFoundException extends AppException {
    public ResourceNotFoundException(String message) {
        super("NOT_FOUND", message);
    }

    public ResourceNotFoundException(ErrorCode code, String message) {
        super(code.name(), message);
    }

    public ResourceNotFoundException(String code, String message) {
        super(code, message);
    }
}