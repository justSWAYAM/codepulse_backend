package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;
import jakarta.validation.constraints.*;

import java.util.UUID;

/**
 * Request body for PUT /api/library/questions/{id}.
 * All fields are optional — only non-null values are applied (patch semantics).
 */
public record UpdateLibraryQuestionRequest(

        UUID subjectId,

        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        String description,

        Difficulty difficulty,

        @Min(value = 1, message = "Points must be at least 1")
        @Max(value = 1000, message = "Points must not exceed 1000")
        Integer points,

        @Min(value = 100, message = "Time limit must be at least 100ms")
        @Max(value = 10000, message = "Time limit must not exceed 10000ms")
        Integer timeLimitMs,

        @Min(value = 4096, message = "Memory limit must be at least 4096 KB")
        @Max(value = 512000, message = "Memory limit must not exceed 512000 KB")
        Integer memoryLimitKb,

        // SQL-specific
        String schemaSql,
        Boolean orderMatters,

        // THEORY-specific
        String modelAnswer

) {}
