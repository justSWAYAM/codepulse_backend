package com.codepulse_backend.contest.dto;

import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.user.dto.UserSummaryResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContestDetailResponse(
        UUID id,
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        int durationMinutes,
        List<String> allowedLanguages,
        ContestStatus status,
        long candidateCount,
        Instant createdAt,
        List<UserSummaryResponse> candidates  // null for CANDIDATE role (scoped via backend)
) {}
