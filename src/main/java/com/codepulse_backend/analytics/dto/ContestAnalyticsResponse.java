package com.codepulse_backend.analytics.dto;

import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.ReviewReason;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contest overview. Counts only: no candidate identities (plan 2.8). Ratios are 0..1,
 * scores have 2 decimals.
 */
public record ContestAnalyticsResponse(
        UUID contestId,
        String contestTitle,
        ContestStatus contestStatus,
        int durationMinutes,
        Coverage coverage,
        ScoreSummary scores,
        List<Bucket> scoreDistribution,
        List<Bucket> timeDistribution,
        long noTimeCount,
        SessionOutcomes sessions,
        AttentionSummary attention
) {
    /** From Module 9's readiness; provisional while anything can still change. */
    public record Coverage(
            int totalCandidates,
            int withResult,
            int inProgress,
            int judging,
            int absent,
            boolean contestCompleted,
            boolean provisional
    ) {}

    public record ScoreSummary(
            BigDecimal maxScore,
            long participants,
            BigDecimal mean,
            BigDecimal median,
            BigDecimal min,
            BigDecimal max,
            BigDecimal stdDev,
            Long medianTimeSeconds
    ) {}

    /** index 1..10; fromRatio/toRatio 0.0..1.0 of the max score or of the contest duration. */
    public record Bucket(int index, BigDecimal fromRatio, BigDecimal toRatio, long count) {}

    public record SessionOutcomes(long submitted, long autoSubmitted, long inProgress, long notStarted) {}

    public record AttentionSummary(
            long needsReview,
            Map<ReviewReason, Long> needsReviewByReason,
            long adjusted,
            long autoSubmitted,
            long absent,
            long zeroScores
    ) {}
}
