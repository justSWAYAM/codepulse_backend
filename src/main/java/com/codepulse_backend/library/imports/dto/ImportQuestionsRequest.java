package com.codepulse_backend.library.imports.dto;

import com.codepulse_backend.common.enums.QuestionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ImportQuestionsRequest(
        @NotNull UUID subjectId,
        @NotNull QuestionType type,
        @NotBlank String payload) {
}
