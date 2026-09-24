package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;
import jakarta.validation.constraints.*;

/**
 * Partial update — all fields are nullable. Only provided fields are updated.
 * Use Integer (boxed) instead of int so null means "don't update".
 */
public record UpdateQuestionRequest(

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

        @Min(value = 4096, message = "Memory limit must be at least 4096 KB (4 MB)")
        @Max(value = 1048576, message = "Memory limit must not exceed 1048576 KB (1 GB)")
        Integer memoryLimitKb
) {}
