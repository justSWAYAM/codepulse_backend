package com.codepulse_backend.contest.dto;

import com.codepulse_backend.common.enums.ContestStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContestResponse(
        UUID id,
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        int durationMinutes,
        List<String> allowedLanguages,
        ContestStatus status,
        long candidateCount,
        Instant createdAt
) {}
