package com.codepulse_backend.execution.event;

import com.codepulse_backend.common.enums.TestCaseResultStatus;

import java.util.List;
import java.util.UUID;

public record SubmissionEvaluatedEvent(
        UUID submissionId,
        List<TestCaseOutcome> outcomes
) {

    public record TestCaseOutcome(
            UUID testCaseId,
            TestCaseResultStatus status,
            String stdout,
            String stderr,
            String compileOutput,
            Double executionTimeMs,
            Integer memoryUsedKb
    ) {}
}