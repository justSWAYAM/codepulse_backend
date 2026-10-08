package com.codepulse_backend.library.imports.dto;

import com.codepulse_backend.user.dto.BulkImportResult;

import java.util.List;

public record QuestionImportResponse(
        boolean dryRun,
        BulkImportResult result,          // Module 2 type, unchanged
        List<ImportRowPreview> rows) {
}
