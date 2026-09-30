package com.codepulse_backend.execution.dto;

import java.util.List;
import java.util.UUID;

public record QueuedSubmissionJob(
        UUID submissionId,
        UUID questionId,
        UUID sessionId,
        String sourceCode,
        String languageName,
        List<TestCasePayload> testCases
) {

    public record TestCasePayload(
            UUID testCaseId,
            String input,
            long timeLimitMs,
            long memoryLimitKb
    ) {}
}