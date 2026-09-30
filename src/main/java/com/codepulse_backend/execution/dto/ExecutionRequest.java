package com.codepulse_backend.execution.dto;

import java.util.UUID;

public record ExecutionRequest(
        UUID submissionId,
        UUID testCaseId,
        String sourceCode,
        String languageName,
        String stdin,
        String expectedOutput,
        long timeLimitMs,
        long memoryLimitKb
) {}