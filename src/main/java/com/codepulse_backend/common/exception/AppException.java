package com.codepulse_backend.common.exception;

import lombok.Getter;

@Getter
public abstract class AppException extends RuntimeException {
    private final String code;

    protected AppException(String code, String message) {
        super(message);
        this.code = code;
    }
}