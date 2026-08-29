package com.codepulse_backend.contest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record UpdateContestRequest(
        @Size(min = 3, max = 255, message = "Title must be between 3 and 255 characters")
        String title,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        Instant startTime,

        Instant endTime,

        @Min(value = 1, message = "Duration must be at least 1 minute")
        Integer durationMinutes,

        @Size(min = 1, message = "At least one language must be selected")
        List<String> allowedLanguages
) {}
