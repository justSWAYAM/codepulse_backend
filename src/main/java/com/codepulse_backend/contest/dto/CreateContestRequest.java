package com.codepulse_backend.contest.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public record CreateContestRequest(
        @NotBlank(message = "Title is required")
        @Size(min = 3, max = 255, message = "Title must be between 3 and 255 characters")
        String title,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        @NotNull(message = "Start time is required")
        @Future(message = "Start time must be in the future")
        Instant startTime,

        @NotNull(message = "End time is required")
        Instant endTime,

        @NotNull(message = "Duration is required")
        @Min(value = 1, message = "Duration must be at least 1 minute")
        Integer durationMinutes,

        @NotEmpty(message = "At least one language must be selected")
        @Size(min = 1, message = "At least one language must be selected")
        List<String> allowedLanguages
) {}
