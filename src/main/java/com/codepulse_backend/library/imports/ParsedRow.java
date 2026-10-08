package com.codepulse_backend.library.imports;

import com.codepulse_backend.library.imports.dto.ImportQuestionItem;

public record ParsedRow(int rowNumber, ImportQuestionItem item, String parseError) {
    public static ParsedRow ok(int n, ImportQuestionItem item) {
        return new ParsedRow(n, item, null);
    }

    public static ParsedRow bad(int n, String error) {
        return new ParsedRow(n, null, error);
    }
}
