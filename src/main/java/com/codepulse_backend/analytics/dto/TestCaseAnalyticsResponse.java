package com.codepulse_backend.analytics.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Pass rates over each candidate's counted submission (plan 2.7). Deliberately has no
 * input, expected or actual output: staff open the test case in Module 5 for those.
 */
public record TestCaseAnalyticsResponse(
        UUID questionId,
        String questionTitle,
        long candidatesEvaluated,
        List<TestCaseStats> testCases
) {
    public record TestCaseStats(
            UUID testCaseId,
            int orderIndex,
            boolean sample,
            int weight,
            long evaluated,
            long passed,
            BigDecimal passRate,
            long wrongAnswer,
            long timeLimit,
            long memoryLimit,
            long runtimeError,
            long compilationError,
            boolean suspicious
    ) {}
}
