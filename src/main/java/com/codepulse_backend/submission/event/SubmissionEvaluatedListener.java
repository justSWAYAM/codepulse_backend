package com.codepulse_backend.submission.event;

import com.codepulse_backend.execution.event.SubmissionEvaluatedEvent;
import com.codepulse_backend.common.enums.TestCaseResultStatus;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import com.codepulse_backend.submission.service.ScoringService;
import com.codepulse_backend.submission.service.SubmissionPersistenceService;
import com.codepulse_backend.testcase.entity.TestCase;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubmissionEvaluatedListener {

    private final SubmissionRepository submissionRepository;
    private final QuestionRepository questionRepository;
    private final TestCaseRepository testCaseRepository;
    private final SubmissionPersistenceService persistenceService;
    private final ScoringService scoringService;

    @EventListener
    public void handle(SubmissionEvaluatedEvent event) {
        try {
            process(event);
        } catch (Exception e) {
            log.error(
                    "Failed to process submission evaluation: submissionId={}",
                    event.submissionId(),
                    e
            );

            // Deliberately leave the submission PENDING.
            // Recovery scheduler will retry it.
        }
    }

    private void process(SubmissionEvaluatedEvent event) {

        Submission submission = submissionRepository.findById(event.submissionId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Submission not found: " + event.submissionId()
                        ));

        if (submission.getStatus() != SubmissionStatus.PENDING) {
            log.info(
                    "Ignoring evaluation for non-pending submission: submissionId={}, status={}",
                    submission.getId(),
                    submission.getStatus()
            );
            return;
        }

        Question question = questionRepository.findById(submission.getQuestionId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Question not found: " + submission.getQuestionId()
                        ));

        List<TestCase> testCases =
                testCaseRepository.findByQuestionIdOrderByOrderIndexAsc(
                        submission.getQuestionId()
                );

        Map<UUID, TestCase> testCaseMap = testCases.stream()
                .collect(Collectors.toMap(
                        TestCase::getId,
                        Function.identity()
                ));

        List<SubmissionTestCaseResult> results = new ArrayList<>();
        List<ScoringService.ScoredResult> scoredResults = new ArrayList<>();

        int passedCount = 0;
        String compileOutput = null;

        for (SubmissionEvaluatedEvent.TestCaseOutcome outcome : event.outcomes()) {

            TestCase testCase = testCaseMap.get(outcome.testCaseId());

            if (testCase == null) {
                throw new IllegalStateException(
                        "Test case not found: " + outcome.testCaseId()
                );
            }

            SubmissionTestCaseResult result = new SubmissionTestCaseResult();

            result.setTestCaseId(testCase.getId());
            result.setStatus(outcome.status());
            result.setActualOutput(truncate(outcome.stdout(), 10_000));
            result.setStderr(truncate(outcome.stderr(), 4_000));

            result.setExecutionTimeMs(
                    outcome.executionTimeMs() == null
                            ? null
                            : BigDecimal.valueOf(outcome.executionTimeMs())
            );

            result.setMemoryUsedKb(outcome.memoryUsedKb());

            result.setWeight(testCase.getWeight());
            result.setSample(testCase.isSample());

            results.add(result);

            scoredResults.add(
                    new ScoringService.ScoredResult(
                            outcome.status(),
                            testCase.getWeight()
                    )
            );

            if (outcome.status() == TestCaseResultStatus.PASSED) {
                passedCount++;
            }

            if (compileOutput == null
                    && outcome.compileOutput() != null
                    && !outcome.compileOutput().isBlank()) {

                compileOutput = truncate(outcome.compileOutput(), 10_000);
            }
        }

        SubmissionStatus finalStatus =
                scoringService.resolveStatus(scoredResults);

        BigDecimal score =
                scoringService.computeScore(
                        BigDecimal.valueOf(question.getPoints()),
                        scoredResults
                );

        persistenceService.applyEvaluation(
                submission.getId(),
                results,
                score,
                finalStatus,
                passedCount,
                event.outcomes().size(),
                compileOutput,
                Instant.now()
        );

        log.info(
                "Submission evaluated: submissionId={}, status={}, score={}, passed={}/{}",
                submission.getId(),
                finalStatus,
                score,
                passedCount,
                event.outcomes().size()
        );
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }

        return value.length() <= maxChars
                ? value
                : value.substring(0, maxChars);
    }
}