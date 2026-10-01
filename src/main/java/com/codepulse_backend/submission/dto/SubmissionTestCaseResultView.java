package com.codepulse_backend.submission.dto;

import com.codepulse_backend.common.enums.TestCaseResultStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record SubmissionTestCaseResultView(
        UUID testCaseId,
        TestCaseResultStatus status,
        String actualOutput,
        String stderr,
        BigDecimal executionTimeMs,
        Integer memoryUsedKb,
        int weight,
        boolean sample
) {}
