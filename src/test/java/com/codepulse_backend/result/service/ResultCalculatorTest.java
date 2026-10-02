package com.codepulse_backend.result.service;

import com.codepulse_backend.common.enums.ResultStatus;
import com.codepulse_backend.common.enums.ReviewReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResultCalculatorTest {

    private final ResultCalculator calculator = new ResultCalculator();

    private final UUID q1 = UUID.randomUUID();
    private final UUID q2 = UUID.randomUUID();
    private final List<ResultCalculator.QuestionInput> questions = List.of(
            new ResultCalculator.QuestionInput(q1, 100),
            new ResultCalculator.QuestionInput(q2, 50));
    private final Instant start = Instant.parse("2026-10-03T10:00:00Z");

    private ResultCalculator.CountedInput counted(UUID id, String score, int minutesAfterStart) {
        return new ResultCalculator.CountedInput(id, new BigDecimal(score), start.plusSeconds(minutesAfterStart * 60L));
    }

    @Test
    void noSubmissionsScoresZeroWithNoTime() {
        ResultCalculator.CandidateResult r = calculator.calculate(questions, Map.of(), Map.of(), 0, start);

        assertEquals(new BigDecimal("0.00"), r.totalScore());
        assertEquals(new BigDecimal("150.00"), r.maxScore());
        assertNull(r.timeTakenSeconds());
        assertEquals(ResultStatus.SCORED, r.status());
        assertEquals(2, r.questions().size());
        assertNull(r.questions().get(0).countedSubmissionId());
    }

    @Test
    void sumsCountedScoresAndTimesTheLastOne() {
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();

        ResultCalculator.CandidateResult r = calculator.calculate(questions,
                Map.of(q1, counted(s1, "75.5", 30), q2, counted(s2, "50", 12)), Map.of(), 0, start);

        assertEquals(new BigDecimal("125.50"), r.totalScore());
        assertEquals(new BigDecimal("125.50"), r.autoScore());
        assertEquals(30 * 60L, r.timeTakenSeconds());
        assertFalse(r.adjusted());
        assertEquals(s1, r.questions().get(0).countedSubmissionId());
    }

    @Test
    void overrideReplacesTheQuestionScore() {
        UUID s1 = UUID.randomUUID(), eval = UUID.randomUUID();

        ResultCalculator.CandidateResult r = calculator.calculate(questions,
                Map.of(q1, counted(s1, "100", 20)),
                Map.of(q1, new ResultCalculator.OverrideInput(eval, s1, new BigDecimal("40"))),
                0, start);

        assertEquals(new BigDecimal("40.00"), r.totalScore());
        assertEquals(new BigDecimal("100.00"), r.autoScore());
        assertTrue(r.adjusted());
        assertEquals(eval, r.questions().get(0).manualEvaluationId());
        assertFalse(r.questions().get(0).overrideOutdated());
        assertEquals(ResultStatus.SCORED, r.status());
    }

    @Test
    void overrideOnAnOlderSubmissionStillAppliesButIsFlagged() {
        UUID oldSubmission = UUID.randomUUID(), nowCounted = UUID.randomUUID();

        ResultCalculator.CandidateResult r = calculator.calculate(questions,
                Map.of(q1, counted(nowCounted, "90", 20)),
                Map.of(q1, new ResultCalculator.OverrideInput(UUID.randomUUID(), oldSubmission, new BigDecimal("10"))),
                0, start);

        assertEquals(new BigDecimal("10.00"), r.totalScore());
        assertTrue(r.questions().get(0).overrideOutdated());
        assertEquals(ResultStatus.NEEDS_REVIEW, r.status());
        assertEquals(List.of(ReviewReason.OVERRIDE_OUTDATED), r.reviewReasons());
    }

    @Test
    void systemErrorSubmitFlagsTheResult() {
        ResultCalculator.CandidateResult r = calculator.calculate(questions, Map.of(), Map.of(), 1, start);

        assertEquals(ResultStatus.NEEDS_REVIEW, r.status());
        assertEquals(List.of(ReviewReason.UNRESOLVED_SYSTEM_ERROR), r.reviewReasons());
    }

    @Test
    void absentHasZerosAndOneRowPerQuestion() {
        ResultCalculator.CandidateResult r = calculator.absent(questions);

        assertEquals(ResultStatus.ABSENT, r.status());
        assertEquals(new BigDecimal("0.00"), r.totalScore());
        assertEquals(new BigDecimal("150.00"), r.maxScore());
        assertEquals(2, r.questions().size());
    }
}
