package com.codepulse_backend.result.dto;

import com.codepulse_backend.common.enums.ResultStatus;
import com.codepulse_backend.common.enums.ReviewReason;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record LeaderboardEntry(
        UUID resultId,
        Integer rank,
        UUID candidateId,
        String candidateName,
        String candidateEmail,
        String candidateRollNumber,
        ResultStatus status,
        List<ReviewReason> reviewReasons,
        BigDecimal totalScore,
        BigDecimal autoScore,
        BigDecimal maxScore,
        boolean adjusted,
        Long timeTakenSeconds,
        List<QuestionScoreCell> questionScores
) {
    public record QuestionScoreCell(
            UUID questionId,
            BigDecimal finalScore,
            int maxPoints,
            boolean attempted,
            boolean adjusted
    ) {}
}
