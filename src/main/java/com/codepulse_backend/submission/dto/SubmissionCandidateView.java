package com.codepulse_backend.submission.dto;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubmissionCandidateView(
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
        String sourceCode,
        String compileOutput,
        List<SampleResultView> sampleResults,
        HiddenSummary hiddenSummary
) {

    public record SampleResultView(
            UUID testCaseId,
            String input,
            String actualOutput,
            String stderr,
            String status,
            BigDecimal timeMs,
            Integer memoryKb
    ) {}

    public record HiddenSummary(
            int passed,
            int total
    ) {}
}