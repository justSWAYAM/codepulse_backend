package com.codepulse_backend.submission.dto;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubmissionDetailResponse(
        UUID id,
        UUID questionId,
        SubmissionType type,
        String language,
        SubmissionStatus status,
        BigDecimal score,
        Integer passedCount,
        Integer totalCount,
        Instant submittedAt,
        Instant evaluatedAt,
        String compileOutput,
        List<TestCaseResultResponse> testCases
) {

    public record TestCaseResultResponse(
            UUID testCaseId,
            String status,
            String actualOutput,
            String stderr,
            BigDecimal executionTimeMs,
            Integer memoryUsedKb
    ) {}

    public static SubmissionDetailResponse from(
            Submission submission,
            List<SubmissionTestCaseResult> results
    ) {
        return new SubmissionDetailResponse(
                submission.getId(),
                submission.getQuestionId(),
                submission.getSubmissionType(),
                submission.getLanguage(),
                submission.getStatus(),
                submission.getScore(),
                submission.getPassedCount(),
                submission.getTotalCount(),
                submission.getSubmittedAt(),
                submission.getEvaluatedAt(),
                submission.getCompileOutput(),
                results.stream()
                        .map(result -> new TestCaseResultResponse(
                                result.getTestCaseId(),
                                result.getStatus().name(),
                                result.getActualOutput(),
                                result.getStderr(),
                                result.getExecutionTimeMs(),
                                result.getMemoryUsedKb()
                        ))
                        .toList()
        );
    }
}