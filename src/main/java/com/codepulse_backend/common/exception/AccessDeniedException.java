package com.codepulse_backend.common.exception;

public class AccessDeniedException extends AppException {
    public AccessDeniedException(String message) {
        super("ACCESS_DENIED", message);
    }
}
