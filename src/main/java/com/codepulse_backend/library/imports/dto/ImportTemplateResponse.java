package com.codepulse_backend.library.imports.dto;

import com.codepulse_backend.common.enums.QuestionType;

public record ImportTemplateResponse(
        QuestionType type,
        String prompt,
        int maxQuestions,
        long maxPayloadBytes) {
}
