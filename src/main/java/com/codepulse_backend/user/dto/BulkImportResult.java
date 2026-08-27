package com.codepulse_backend.user.dto;

import java.util.List;

public record BulkImportResult(
        int totalRows,
        int succeededCount,
        int failedCount,
        List<RowError> errors
) {
    public record RowError(
            int rowNumber,
            String reason
    ) {}
}