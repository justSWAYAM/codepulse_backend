package com.codepulse_backend.submission.service;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.TestCaseResultStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScoringServiceTest {

    private final ScoringService scoringService = new ScoringService();

    @Test
    void allPassedShouldReceiveFullScore() {

        List<ScoringService.ScoredResult> results = List.of(
                passed(1),
                passed(1),
                passed(1)
        );

        BigDecimal score = scoringService.computeScore(
                new BigDecimal("100"),
                results
        );

        assertEquals(
                new BigDecimal("100.00"),
                score
        );

        assertEquals(
                SubmissionStatus.ACCEPTED,
                scoringService.resolveStatus(results)
        );
    }

    @Test
    void weightedPartialCreditShouldBeCalculatedCorrectly() {

        List<ScoringService.ScoredResult> results = List.of(
                passed(2),
                wrongAnswer(1),
                passed(1)
        );

        BigDecimal score = scoringService.computeScore(
                new BigDecimal("100"),
                results
        );

        // Passed weight = 3
        // Total weight = 4
        // 100 * 3 / 4 = 75
        assertEquals(
                new BigDecimal("75.00"),
                score
        );
    }

    @Test
    void zeroWeightsShouldUseEqualWeight() {

        List<ScoringService.ScoredResult> results = List.of(
                passed(0),
                wrongAnswer(0),
                passed(0),
                wrongAnswer(0)
        );

        BigDecimal score = scoringService.computeScore(
                new BigDecimal("100"),
                results
        );

        // 2 / 4 passed = 50%
        assertEquals(
                new BigDecimal("50.00"),
                score
        );
    }

    @Test
    void compilationErrorShouldGiveZeroScore() {

        List<ScoringService.ScoredResult> results = List.of(
                compilationError(1),
                passed(1)
        );

        BigDecimal score = scoringService.computeScore(
                new BigDecimal("100"),
                results
        );

        assertEquals(
                new BigDecimal("0.00"),
                score
        );

        assertEquals(
                SubmissionStatus.COMPILATION_ERROR,
                scoringService.resolveStatus(results)
        );
    }

    @Test
    void systemErrorShouldNotBeScored() {

        List<ScoringService.ScoredResult> results = List.of(
                passed(1),
                systemError(1)
        );

        BigDecimal score = scoringService.computeScore(
                new BigDecimal("100"),
                results
        );

        assertNull(score);

        assertEquals(
                SubmissionStatus.SYSTEM_ERROR,
                scoringService.resolveStatus(results)
        );
    }

    @Test
    void systemErrorShouldHaveHighestStatusPrecedence() {

        List<ScoringService.ScoredResult> results = List.of(
                wrongAnswer(1),
                compilationError(1),
                systemError(1)
        );

        assertEquals(
                SubmissionStatus.SYSTEM_ERROR,
                scoringService.resolveStatus(results)
        );
    }

    @Test
    void compilationErrorShouldTakePrecedenceOverRuntimeError() {

        List<ScoringService.ScoredResult> results = List.of(
                runtimeError(1),
                compilationError(1)
        );

        assertEquals(
                SubmissionStatus.COMPILATION_ERROR,
                scoringService.resolveStatus(results)
        );
    }

    @Test
    void firstNonPassedResultShouldDetermineStatus() {

        List<ScoringService.ScoredResult> results = List.of(
                passed(1),
                wrongAnswer(1),
                runtimeError(1),
                timeLimitExceeded(1)
        );

        assertEquals(
                SubmissionStatus.WRONG_ANSWER,
                scoringService.resolveStatus(results)
        );
    }

    @Test
    void allPassedStatusShouldBeAccepted() {

        List<ScoringService.ScoredResult> results = List.of(
                passed(1),
                passed(2),
                passed(3)
        );

        assertEquals(
                SubmissionStatus.ACCEPTED,
                scoringService.resolveStatus(results)
        );
    }

    private ScoringService.ScoredResult passed(int weight) {
        return new ScoringService.ScoredResult(
                TestCaseResultStatus.PASSED,
                weight
        );
    }

    private ScoringService.ScoredResult wrongAnswer(int weight) {
        return new ScoringService.ScoredResult(
                TestCaseResultStatus.WRONG_ANSWER,
                weight
        );
    }

    private ScoringService.ScoredResult compilationError(int weight) {
        return new ScoringService.ScoredResult(
                TestCaseResultStatus.COMPILATION_ERROR,
                weight
        );
    }

    private ScoringService.ScoredResult runtimeError(int weight) {
        return new ScoringService.ScoredResult(
                TestCaseResultStatus.RUNTIME_ERROR,
                weight
        );
    }

    private ScoringService.ScoredResult timeLimitExceeded(int weight) {
        return new ScoringService.ScoredResult(
                TestCaseResultStatus.TIME_LIMIT_EXCEEDED,
                weight
        );
    }

    private ScoringService.ScoredResult systemError(int weight) {
        return new ScoringService.ScoredResult(
                TestCaseResultStatus.SYSTEM_ERROR,
                weight
        );
    }
}