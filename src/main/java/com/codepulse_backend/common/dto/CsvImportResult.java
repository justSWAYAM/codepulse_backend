package com.codepulse_backend.common.dto;

import java.util.List;

public record CsvImportResult(
        int totalRows,
        int succeededCount,
        int failedCount,
        List<RowError> errors
) {}