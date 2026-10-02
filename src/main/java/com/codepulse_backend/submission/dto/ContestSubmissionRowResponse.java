package com.codepulse_backend.submission.dto;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.user.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row in the evaluator/admin contest submissions table.
 * Carries the candidate's identity so the UI doesn't need N user lookups.
 */
public record ContestSubmissionRowResponse(
        UUID id,
        UUID questionId,
        UUID candidateId,
        String candidateName,
        String candidateEmail,
        String candidateRollNumber,
        SubmissionType type,
        String language,
        SubmissionStatus status,
        BigDecimal score,
        Integer passedCount,
        Integer totalCount,
        Instant submittedAt
) {

    public static ContestSubmissionRowResponse from(
            Submission submission,
            User candidate
    ) {
        return new ContestSubmissionRowResponse(
                submission.getId(),
                submission.getQuestionId(),
                submission.getCandidateId(),
                candidate != null ? candidate.getFullName() : null,
                candidate != null ? candidate.getEmail() : null,
                candidate != null ? candidate.getRollNumber() : null,
                submission.getSubmissionType(),
                submission.getLanguage(),
                submission.getStatus(),
                submission.getScore(),
                submission.getPassedCount(),
                submission.getTotalCount(),
                submission.getSubmittedAt()
        );
    }
}
