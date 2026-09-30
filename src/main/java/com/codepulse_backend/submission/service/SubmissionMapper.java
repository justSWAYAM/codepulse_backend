package com.codepulse_backend.submission.service;

import com.codepulse_backend.submission.dto.SubmissionCandidateView;
import com.codepulse_backend.submission.dto.SubmissionEvaluatorView;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;
import com.codepulse_backend.testcase.entity.TestCase;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SubmissionMapper {

    private final TestCaseRepository testCaseRepository;

    public SubmissionCandidateView toCandidateView(
            Submission submission,
            List<SubmissionTestCaseResult> results,
            boolean resultsVisible
    ) {
        if (!resultsVisible) {
            return new SubmissionCandidateView(
                    submission.getId(),
                    submission.getQuestionId(),
                    submission.getSubmissionType(),
                    submission.getLanguage(),
                    null,
                    null,
                    null,
                    null,
                    submission.getSubmittedAt(),
                    submission.getEvaluatedAt(),
                    submission.getSourceCode(),
                    submission.getCompileOutput(),
                    null,
                    null
            );
        }

        Map<UUID, TestCase> testCases = loadTestCases(results);

        List<SubmissionCandidateView.SampleResultView> sampleResults =
                results.stream()
                        .filter(SubmissionTestCaseResult::isSample)
                        .map(result -> {
                            TestCase testCase = testCases.get(result.getTestCaseId());

                            return new SubmissionCandidateView.SampleResultView(
                                    result.getTestCaseId(),
                                    testCase != null ? testCase.getInput() : null,
                                    result.getActualOutput(),
                                    result.getStderr(),
                                    result.getStatus().name(),
                                    result.getExecutionTimeMs(),
                                    result.getMemoryUsedKb()
                            );
                        })
                        .toList();

        int hiddenPassed = (int) results.stream()
                .filter(result -> !result.isSample())
                .filter(result ->
                        result.getStatus().name().equals("PASSED"))
                .count();

        int hiddenTotal = (int) results.stream()
                .filter(result -> !result.isSample())
                .count();

        return new SubmissionCandidateView(
                submission.getId(),
                submission.getQuestionId(),
                submission.getSubmissionType(),
                submission.getLanguage(),
                submission.getStatus(),
                submission.getScore(),
                submission.getPassedCount(),
                submission.getTotalCount(),
                submission.getSubmittedAt(),
                submission.getEvaluatedAt(),
                submission.getSourceCode(),
                submission.getCompileOutput(),
                sampleResults,
                new SubmissionCandidateView.HiddenSummary(
                        hiddenPassed,
                        hiddenTotal
                )
        );
    }

    public SubmissionEvaluatorView toEvaluatorView(
            Submission submission,
            List<SubmissionTestCaseResult> results
    ) {
        Map<UUID, TestCase> testCases = loadTestCases(results);

        List<SubmissionEvaluatorView.TestCaseResultView> testCaseResults =
                results.stream()
                        .map(result -> {
                            TestCase testCase = testCases.get(result.getTestCaseId());

                            return new SubmissionEvaluatorView.TestCaseResultView(
                                    result.getTestCaseId(),
                                    testCase != null ? testCase.getInput() : null,
                                    testCase != null
                                            ? testCase.getExpectedOutput()
                                            : null,
                                    result.getActualOutput(),
                                    result.getStderr(),
                                    result.getStatus().name(),
                                    result.getExecutionTimeMs(),
                                    result.getMemoryUsedKb(),
                                    result.getWeight(),
                                    result.isSample()
                            );
                        })
                        .toList();

        return new SubmissionEvaluatorView(
                submission.getId(),
                submission.getSessionId(),
                submission.getQuestionId(),
                submission.getCandidateId(),
                submission.getSubmissionType(),
                submission.getLanguage(),
                submission.getSourceCode(),
                submission.getStatus(),
                submission.getScore(),
                submission.getPassedCount(),
                submission.getTotalCount(),
                submission.getCompileOutput(),
                submission.getSubmittedAt(),
                submission.getEvaluatedAt(),
                testCaseResults
        );
    }

    private Map<UUID, TestCase> loadTestCases(
            List<SubmissionTestCaseResult> results
    ) {
        List<UUID> testCaseIds = results.stream()
                .map(SubmissionTestCaseResult::getTestCaseId)
                .distinct()
                .toList();

        if (testCaseIds.isEmpty()) {
            return Map.of();
        }

        return testCaseRepository.findAllById(testCaseIds)
                .stream()
                .collect(Collectors.toMap(
                        TestCase::getId,
                        Function.identity()
                ));
    }
}