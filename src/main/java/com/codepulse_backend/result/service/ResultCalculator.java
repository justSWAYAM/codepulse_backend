package com.codepulse_backend.result.service;

import com.codepulse_backend.common.enums.ResultStatus;
import com.codepulse_backend.common.enums.ReviewReason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure arithmetic for one candidate's result (plan 2.3, 2.4, 2.7). No repositories,
 * so every rule is unit-testable without a database.
 */
@Component
public class ResultCalculator {

    public CandidateResult calculate(
            List<QuestionInput> questionsInOrder,
            Map<UUID, CountedInput> countedByQuestion,
            Map<UUID, OverrideInput> activeOverrides,
            long systemErrorSubmits,
            Instant sessionStartedAt
    ) {
        BigDecimal autoTotal = BigDecimal.ZERO;
        BigDecimal finalTotal = BigDecimal.ZERO;
        BigDecimal maxTotal = BigDecimal.ZERO;
        boolean adjusted = false;
        boolean anyOutdated = false;
        Instant lastCountedAt = null;

        List<QuestionOutcome> outcomes = new ArrayList<>();

        for (QuestionInput question : questionsInOrder) {
            CountedInput counted = countedByQuestion.get(question.questionId());
            OverrideInput override = activeOverrides.get(question.questionId());

            BigDecimal auto = counted == null || counted.score() == null
                    ? BigDecimal.ZERO
                    : counted.score();

            // An override sets the question's score even if the submission it was made
            // against no longer counts; it is then flagged for review (plan 2.6)
            BigDecimal fin = override != null ? override.adjustedScore() : auto;
            boolean outdated = override != null
                    && (counted == null || !counted.submissionId().equals(override.submissionId()));

            outcomes.add(new QuestionOutcome(
                    question.questionId(),
                    question.points(),
                    counted != null ? counted.submissionId() : null,
                    override != null ? override.evaluationId() : null,
                    scale(auto),
                    scale(fin),
                    outdated
            ));

            autoTotal = autoTotal.add(auto);
            finalTotal = finalTotal.add(fin);
            maxTotal = maxTotal.add(BigDecimal.valueOf(question.points()));
            adjusted |= override != null;
            anyOutdated |= outdated;

            if (counted != null && counted.submittedAt() != null
                    && (lastCountedAt == null || counted.submittedAt().isAfter(lastCountedAt))) {
                lastCountedAt = counted.submittedAt();
            }
        }

        List<ReviewReason> reasons = new ArrayList<>();
        if (systemErrorSubmits > 0) {
            reasons.add(ReviewReason.UNRESOLVED_SYSTEM_ERROR);
        }
        if (anyOutdated) {
            reasons.add(ReviewReason.OVERRIDE_OUTDATED);
        }

        Long timeTaken = lastCountedAt == null || sessionStartedAt == null
                ? null
                : Math.max(0L, Duration.between(sessionStartedAt, lastCountedAt).getSeconds());

        return new CandidateResult(
                scale(autoTotal),
                scale(finalTotal),
                scale(maxTotal),
                adjusted,
                timeTaken,
                reasons.isEmpty() ? ResultStatus.SCORED : ResultStatus.NEEDS_REVIEW,
                reasons,
                outcomes
        );
    }

    /** A candidate who never started: zeros everywhere, one zeroed row per question. */
    public CandidateResult absent(List<QuestionInput> questionsInOrder) {
        BigDecimal max = BigDecimal.ZERO;
        List<QuestionOutcome> outcomes = new ArrayList<>();
        for (QuestionInput question : questionsInOrder) {
            max = max.add(BigDecimal.valueOf(question.points()));
            outcomes.add(new QuestionOutcome(question.questionId(), question.points(),
                    null, null, scale(BigDecimal.ZERO), scale(BigDecimal.ZERO), false));
        }
        return new CandidateResult(scale(BigDecimal.ZERO), scale(BigDecimal.ZERO), scale(max),
                false, null, ResultStatus.ABSENT, List.of(), outcomes);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record QuestionInput(UUID questionId, int points) {}

    public record CountedInput(UUID submissionId, BigDecimal score, Instant submittedAt) {}

    /** Only non-revert overrides; a revert simply means "no entry". */
    public record OverrideInput(UUID evaluationId, UUID submissionId, BigDecimal adjustedScore) {}

    public record CandidateResult(
            BigDecimal autoScore,
            BigDecimal totalScore,
            BigDecimal maxScore,
            boolean adjusted,
            Long timeTakenSeconds,
            ResultStatus status,
            List<ReviewReason> reviewReasons,
            List<QuestionOutcome> questions
    ) {}

    public record QuestionOutcome(
            UUID questionId,
            int maxPoints,
            UUID countedSubmissionId,
            UUID manualEvaluationId,
            BigDecimal autoScore,
            BigDecimal finalScore,
            boolean overrideOutdated
    ) {}
}
