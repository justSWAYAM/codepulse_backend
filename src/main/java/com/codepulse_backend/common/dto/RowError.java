package com.codepulse_backend.common.dto;

public record RowError(
        int rowNumber,
        String reason
) {}