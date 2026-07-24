package com.codepulse_backend.common.exception;

public class DuplicateResourceException extends AppException {
    public DuplicateResourceException(String message) {
        super("DUPLICATE_RESOURCE", message);
    }
}
