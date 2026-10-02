package com.codepulse_backend.submission.service;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.TestCaseResultStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class ScoringService {

    /**
     * Represents the result of one evaluated test case.
     *
     * @param status test-case execution/result status
     * @param weight test-case weight
     */
    public record ScoredResult(
            TestCaseResultStatus status,
            int weight
    ) {
    }

    /**
     * Computes the score for a submission.
     *
     * Rules:
     * - SYSTEM_ERROR -> score is null
     * - COMPILATION_ERROR -> score is 0
     * - total weight 0 -> all test cases receive equal weight
     * - otherwise:
     *
     *   score = questionPoints *
     *           passedWeight / totalWeight
     *
     * Result is rounded to 2 decimal places.
     */
    public BigDecimal computeScore(
            BigDecimal questionPoints,
            List<ScoredResult> results
    ) {
        if (results == null || results.isEmpty()) {
            // No results resolves to SYSTEM_ERROR (resolveStatus), which is never scored
            return null;
        }

        // SYSTEM_ERROR means the submission is not scored.
        boolean hasSystemError = results.stream()
                .anyMatch(result ->
                        result.status() == TestCaseResultStatus.SYSTEM_ERROR
                );

        if (hasSystemError) {
            return null;
        }

        // Compilation failure gets zero score.
        boolean hasCompilationError = results.stream()
                .anyMatch(result ->
                        result.status() == TestCaseResultStatus.COMPILATION_ERROR
                );

        if (hasCompilationError) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        int totalWeight = results.stream()
                .mapToInt(ScoredResult::weight)
                .sum();

        // If all weights are zero, treat every test case equally.
        if (totalWeight == 0) {
            totalWeight = results.size();

            long passedCount = results.stream()
                    .filter(result ->
                            result.status() == TestCaseResultStatus.PASSED
                    )
                    .count();

            return questionPoints
                    .multiply(BigDecimal.valueOf(passedCount))
                    .divide(
                            BigDecimal.valueOf(totalWeight),
                            2,
                            RoundingMode.HALF_UP
                    );
        }

        int passedWeight = results.stream()
                .filter(result ->
                        result.status() == TestCaseResultStatus.PASSED
                )
                .mapToInt(ScoredResult::weight)
                .sum();

        return questionPoints
                .multiply(BigDecimal.valueOf(passedWeight))
                .divide(
                        BigDecimal.valueOf(totalWeight),
                        2,
                        RoundingMode.HALF_UP
                );
    }

    /**
     * Resolves the overall submission status.
     *
     * Precedence:
     * 1. SYSTEM_ERROR
     * 2. COMPILATION_ERROR
     * 3. ACCEPTED if every test case passed
     * 4. Otherwise the first non-passed status in test-case order
     */
    public SubmissionStatus resolveStatus(
            List<ScoredResult> resultsInTestCaseOrder
    ) {
        if (resultsInTestCaseOrder == null ||
                resultsInTestCaseOrder.isEmpty()) {
            return SubmissionStatus.SYSTEM_ERROR;
        }

        boolean hasSystemError = resultsInTestCaseOrder.stream()
                .anyMatch(result ->
                        result.status() == TestCaseResultStatus.SYSTEM_ERROR
                );

        if (hasSystemError) {
            return SubmissionStatus.SYSTEM_ERROR;
        }

        boolean hasCompilationError = resultsInTestCaseOrder.stream()
                .anyMatch(result ->
                        result.status() == TestCaseResultStatus.COMPILATION_ERROR
                );

        if (hasCompilationError) {
            return SubmissionStatus.COMPILATION_ERROR;
        }

        boolean allPassed = resultsInTestCaseOrder.stream()
                .allMatch(result ->
                        result.status() == TestCaseResultStatus.PASSED
                );

        if (allPassed) {
            return SubmissionStatus.ACCEPTED;
        }

        // First non-passed result determines the status.
        return resultsInTestCaseOrder.stream()
                .filter(result ->
                        result.status() != TestCaseResultStatus.PASSED
                )
                .map(result -> mapStatus(result.status()))
                .findFirst()
                .orElse(SubmissionStatus.SYSTEM_ERROR);
    }

    private SubmissionStatus mapStatus(TestCaseResultStatus status) {
        return switch (status) {
            case PASSED -> SubmissionStatus.ACCEPTED;
            case WRONG_ANSWER -> SubmissionStatus.WRONG_ANSWER;
            case TIME_LIMIT_EXCEEDED ->
                    SubmissionStatus.TIME_LIMIT_EXCEEDED;
            case MEMORY_LIMIT_EXCEEDED ->
                    SubmissionStatus.MEMORY_LIMIT_EXCEEDED;
            case COMPILATION_ERROR ->
                    SubmissionStatus.COMPILATION_ERROR;
            case RUNTIME_ERROR ->
                    SubmissionStatus.RUNTIME_ERROR;
            case SYSTEM_ERROR ->
                    SubmissionStatus.SYSTEM_ERROR;
        };
    }


}

