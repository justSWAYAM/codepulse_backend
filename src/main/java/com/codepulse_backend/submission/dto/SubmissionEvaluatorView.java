package com.codepulse_backend.submission.dto;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubmissionEvaluatorView(
        UUID id,
        UUID sessionId,
        UUID questionId,
        UUID candidateId,
        SubmissionType type,
        String language,
        String sourceCode,
        SubmissionStatus status,
        BigDecimal score,
        Integer passedCount,
        Integer totalCount,
        String compileOutput,
        Instant submittedAt,
        Instant evaluatedAt,
        List<TestCaseResultView> testCaseResults
) {

    public record TestCaseResultView(
            UUID testCaseId,
            String input,
            String expectedOutput,
            String actualOutput,
            String stderr,
            String status,
            BigDecimal timeMs,
            Integer memoryKb,
            int weight,
            boolean sample
    ) {}
}