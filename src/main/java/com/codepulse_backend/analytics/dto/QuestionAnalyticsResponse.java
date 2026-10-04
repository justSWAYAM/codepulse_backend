package com.codepulse_backend.analytics.dto;

import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.SubmissionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record QuestionAnalyticsResponse(
        ContestAnalyticsResponse.Coverage coverage,
        List<QuestionStats> questions
) {
    /**
     * Scores from Module 9 (overrides included), activity from Module 8 submissions.
     * observedDifficulty null = not enough data (plan 2.5).
     */
    public record QuestionStats(
            UUID questionId,
            String title,
            int orderIndex,
            int points,
            Difficulty difficulty,
            long participants,
            long attempted,
            long fullMarks,
            long partial,
            long zero,
            BigDecimal averageScore,
            BigDecimal averageRatio,
            Difficulty observedDifficulty,
            boolean difficultyMatches,
            long adjusted,
            long submitCount,
            long runCount,
            BigDecimal attemptsPerCandidate,
            Long medianSecondsToAccepted,
            long solvers,
            List<VerdictCount> verdicts,
            List<LanguageCount> languages
    ) {}

    public record VerdictCount(SubmissionStatus status, long count) {}

    public record LanguageCount(String language, long count) {}
}
