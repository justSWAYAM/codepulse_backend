package com.codepulse_backend.common.dto;

import org.springframework.validation.FieldError;

import java.util.List;

public record ApiError(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String traceId
) {
    public record FieldError(String field,String message) {}
}