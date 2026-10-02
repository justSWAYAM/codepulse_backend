package com.codepulse_backend.result.dto;

import com.codepulse_backend.common.enums.ContestStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Staff leaderboard. Entries are ordered by rank, ABSENT last by name. */
public record LeaderboardResponse(
        UUID contestId,
        String contestTitle,
        ContestStatus contestStatus,
        boolean published,
        Instant publishedAt,
        String publishedByName,
        BigDecimal maxScore,
        List<QuestionColumn> questions,
        ResultReadiness readiness,
        List<LeaderboardEntry> entries
) {
    public record QuestionColumn(UUID questionId, String title, int orderIndex, int points) {}
}
