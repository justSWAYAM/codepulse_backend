package com.codepulse_backend.result.dto;

import com.codepulse_backend.common.enums.ResultStatus;
import com.codepulse_backend.common.enums.ReviewReason;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.session.SessionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Staff view of one candidate's result. Never returned to a candidate. */
public record ResultResponse(
        UUID resultId,
        UUID contestId,
        UUID candidateId,
        String candidateName,
        String candidateEmail,
        String candidateRollNumber,
        UUID sessionId,
        SessionStatus sessionStatus,
        Instant startedAt,
        Instant submittedAt,
        ResultStatus status,
        List<ReviewReason> reviewReasons,
        BigDecimal totalScore,
        BigDecimal autoScore,
        BigDecimal maxScore,
        Integer rank,
        long rankedCount,
        Long timeTakenSeconds,
        boolean published,
        Instant computedAt,
        List<QuestionResultView> questions
) {
    public record QuestionResultView(
            UUID questionId,
            String title,
            int orderIndex,
            int maxPoints,
            BigDecimal autoScore,
            BigDecimal finalScore,
            CountedSubmissionView countedSubmission,
            int submitAttempts,
            int systemErrorCount,
            ManualEvaluationResponse activeOverride,
            boolean overrideOutdated,
            List<ManualEvaluationResponse> history
    ) {}

    public record CountedSubmissionView(
            UUID id,
            String language,
            SubmissionStatus status,
            Integer passedCount,
            Integer totalCount,
            Instant submittedAt
    ) {}
}
