package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

/**
 * Request body for POST /api/library/questions (create a library question).
 *
 * Type-specific fields (schemaSql, orderMatters, modelAnswer, options) are
 * nullable — service validates their presence based on questionType.
 *
 * timeLimitMs and memoryLimitKb default to platform standards when null;
 * they are only meaningful for DSA/SQL questions.
 */
public record CreateLibraryQuestionRequest(

        @NotNull(message = "Subject ID is required")
        UUID subjectId,

        @NotNull(message = "Question type is required")
        QuestionType questionType,

        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        @NotBlank(message = "Description is required")
        String description,

        @NotNull(message = "Difficulty is required")
        Difficulty difficulty,

        @Min(value = 1, message = "Points must be at least 1")
        @Max(value = 1000, message = "Points must not exceed 1000")
        int points,

        // DSA / SQL execution limits — optional for MCQ/THEORY
        @Min(value = 100, message = "Time limit must be at least 100ms")
        @Max(value = 10000, message = "Time limit must not exceed 10000ms")
        Integer timeLimitMs,

        @Min(value = 4096, message = "Memory limit must be at least 4096 KB (4 MB)")
        @Max(value = 512000, message = "Memory limit must not exceed 512000 KB (500 MB)")
        Integer memoryLimitKb,

        // SQL-specific
        String schemaSql,
        Boolean orderMatters,

        // THEORY-specific
        String modelAnswer,

        // MCQ-specific: list of options with correct flags
        List<@Valid McqOptionRequest> options

) {}
