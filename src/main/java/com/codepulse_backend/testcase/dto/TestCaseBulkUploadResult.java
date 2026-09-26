package com.codepulse_backend.testcase.dto;

import com.codepulse_backend.common.dto.RowError;

import java.util.List;

public record TestCaseBulkUploadResult(
        int totalRows,
        int succeededCount,
        int failedCount,
        List<RowError> errors
) {}