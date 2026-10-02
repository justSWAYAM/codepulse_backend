package com.codepulse_backend.result.dto;

import com.codepulse_backend.common.enums.ResultStatus;
import com.codepulse_backend.common.enums.SubmissionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Candidate view. Before publishing, everything after publishedAt is null and
 * questions is empty. Deliberately has no autoScore, comment or evaluator field.
 */
public record MyResultResponse(
        UUID contestId,
        String contestTitle,
        boolean published,
        Instant publishedAt,
        ResultStatus status,
        BigDecimal totalScore,
        BigDecimal maxScore,
        Integer rank,
        Long rankedCount,
        Boolean adjusted,
        List<MyQuestionResult> questions
) {
    public static MyResultResponse unpublished(UUID contestId, String contestTitle) {
        return new MyResultResponse(contestId, contestTitle, false, null,
                null, null, null, null, null, null, List.of());
    }

    public record MyQuestionResult(
            UUID questionId,
            String title,
            int orderIndex,
            int maxPoints,
            BigDecimal finalScore,
            SubmissionStatus verdict,
            Integer passedCount,
            Integer totalCount,
            UUID countedSubmissionId,
            boolean adjusted
    ) {}
}
