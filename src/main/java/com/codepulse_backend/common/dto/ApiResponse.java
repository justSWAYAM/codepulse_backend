package com.codepulse_backend.common.dto;

import java.time.Instant;

public record ApiResponse<T>(
    boolean success,
    T data,
    String message,
    Instant timestamp,
    String traceId
) {
    public static <T> ApiResponse<T> success(T data, String message, String traceId) {
        return new ApiResponse<>(true, data, message, Instant.now(), traceId);
    }

    public static <T> ApiResponse<T> failure(String message,String traceId) {
        return new ApiResponse<>(false,null,message,Instant.now(),traceId);
    }
}

