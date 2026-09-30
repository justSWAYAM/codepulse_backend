package com.codepulse_backend.submission.dto;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.submission.entity.Submission;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SubmissionSummaryResponse(
        UUID id,
        UUID questionId,
        SubmissionType type,
        String language,
        SubmissionStatus status,
        BigDecimal score,
        Integer passedCount,
        Integer totalCount,
        Instant submittedAt,
        boolean counted
) {

    public static SubmissionSummaryResponse from(
            Submission submission,
            boolean counted
    ) {
        return new SubmissionSummaryResponse(
                submission.getId(),
                submission.getQuestionId(),
                submission.getSubmissionType(),
                submission.getLanguage(),
                submission.getStatus(),
                submission.getScore(),
                submission.getPassedCount(),
                submission.getTotalCount(),
                submission.getSubmittedAt(),
                counted
        );
    }
}