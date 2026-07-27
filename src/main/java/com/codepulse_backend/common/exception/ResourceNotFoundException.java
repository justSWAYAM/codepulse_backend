package com.codepulse_backend.common.exception;

public class ResourceNotFoundException extends AppException {
    public ResourceNotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}