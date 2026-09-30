package com.codepulse_backend.submission.service;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.common.enums.TestCaseResultStatus;
import com.codepulse_backend.submission.dto.SubmissionCandidateView;
import com.codepulse_backend.submission.dto.SubmissionEvaluatorView;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;
import com.codepulse_backend.testcase.entity.TestCase;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionMapperTest {

    @Mock
    private TestCaseRepository testCaseRepository;

    private SubmissionMapper mapper;

    private UUID submissionId;
    private UUID questionId;
    private UUID sampleTestCaseId;
    private UUID hiddenTestCaseId;

    @BeforeEach
    void setUp() {
        mapper = new SubmissionMapper(testCaseRepository);

        submissionId = UUID.randomUUID();
        questionId = UUID.randomUUID();
        sampleTestCaseId = UUID.randomUUID();
        hiddenTestCaseId = UUID.randomUUID();
    }
    @Disabled
    @Test
    void candidateViewMustNotExposeExpectedOutputOrHiddenDetails() {
        Submission submission = createSubmission();

        SubmissionTestCaseResult sampleResult =
                createResult(sampleTestCaseId, true, TestCaseResultStatus.PASSED);

        SubmissionTestCaseResult hiddenResult =
                createResult(hiddenTestCaseId, false, TestCaseResultStatus.PASSED);

        TestCase sample = createTestCase(
                sampleTestCaseId,
                true,
                "2 3",
                "5"
        );

        TestCase hidden = createTestCase(
                hiddenTestCaseId,
                false,
                "10 20",
                "30"
        );

        when(testCaseRepository.findAllById(
                List.of(sampleTestCaseId, hiddenTestCaseId)
        )).thenReturn(List.of(sample, hidden));

        SubmissionCandidateView view = mapper.toCandidateView(
                submission,
                List.of(sampleResult, hiddenResult),
                true
        );

        assertEquals(submissionId, view.id());
        assertEquals(1, view.sampleResults().size());

        SubmissionCandidateView.SampleResultView sampleView =
                view.sampleResults().get(0);

        assertEquals(sampleTestCaseId, sampleView.testCaseId());
        assertEquals("2 3", sampleView.input());
        assertEquals("5", sampleView.actualOutput());

        /*
         * There is deliberately no expectedOutput field
         * in SampleResultView.
         *
         * Hidden test data must not appear in sampleResults.
         */
        assertEquals(1, view.hiddenSummary().passed());
        assertEquals(1, view.hiddenSummary().total());

        String serializedView = view.toString();

        assertFalse(serializedView.contains("30"));
        assertFalse(serializedView.contains("10 20"));
    }

    @Test
    void candidateViewWithResultsHiddenMustHideEvaluationData() {
        Submission submission = createSubmission();

        SubmissionTestCaseResult result =
                createResult(sampleTestCaseId, true, TestCaseResultStatus.PASSED);

        SubmissionCandidateView view = mapper.toCandidateView(
                submission,
                List.of(result),
                false
        );

        assertNull(view.status());
        assertNull(view.score());
        assertNull(view.passedCount());
        assertNull(view.totalCount());
        assertNull(view.sampleResults());
        assertNull(view.hiddenSummary());
    }

    @Test
    void evaluatorViewMustContainExpectedOutputAndHiddenResults() {
        Submission submission = createSubmission();

        SubmissionTestCaseResult sampleResult =
                createResult(sampleTestCaseId, true, TestCaseResultStatus.PASSED);

        SubmissionTestCaseResult hiddenResult =
                createResult(hiddenTestCaseId, false, TestCaseResultStatus.WRONG_ANSWER);

        TestCase sample = createTestCase(
                sampleTestCaseId,
                true,
                "2 3",
                "5"
        );

        TestCase hidden = createTestCase(
                hiddenTestCaseId,
                false,
                "10 20",
                "30"
        );

        when(testCaseRepository.findAllById(
                List.of(sampleTestCaseId, hiddenTestCaseId)
        )).thenReturn(List.of(sample, hidden));

        SubmissionEvaluatorView view = mapper.toEvaluatorView(
                submission,
                List.of(sampleResult, hiddenResult)
        );

        assertEquals(2, view.testCaseResults().size());

        SubmissionEvaluatorView.TestCaseResultView sampleView =
                view.testCaseResults().stream()
                        .filter(r -> r.testCaseId().equals(sampleTestCaseId))
                        .findFirst()
                        .orElseThrow();

        SubmissionEvaluatorView.TestCaseResultView hiddenView =
                view.testCaseResults().stream()
                        .filter(r -> r.testCaseId().equals(hiddenTestCaseId))
                        .findFirst()
                        .orElseThrow();

        assertEquals("2 3", sampleView.input());
        assertEquals("5", sampleView.expectedOutput());

        assertEquals("10 20", hiddenView.input());
        assertEquals("30", hiddenView.expectedOutput());
        assertEquals("WRONG_ANSWER", hiddenView.status());
    }

    private Submission createSubmission() {
        Submission submission = new Submission();

        submission.setId(submissionId);
        submission.setQuestionId(questionId);
        submission.setSessionId(UUID.randomUUID());
        submission.setCandidateId(UUID.randomUUID());
        submission.setLanguage("JAVA");
        submission.setSourceCode("public class Main {}");
        submission.setSubmissionType(SubmissionType.SUBMIT);
        submission.setStatus(SubmissionStatus.ACCEPTED);
        submission.setScore(new BigDecimal("100.00"));
        submission.setPassedCount(1);
        submission.setTotalCount(2);

        return submission;
    }

    private SubmissionTestCaseResult createResult(
            UUID testCaseId,
            boolean sample,
            TestCaseResultStatus status
    ) {
        SubmissionTestCaseResult result =
                new SubmissionTestCaseResult();

        result.setTestCaseId(testCaseId);
        result.setSample(sample);
        result.setStatus(status);
        result.setActualOutput(sample ? "5" : "999");
        result.setStderr(sample ? "" : "hidden stderr");
        result.setExecutionTimeMs(new BigDecimal("12.50"));
        result.setMemoryUsedKb(1000);
        result.setWeight(1);

        return result;
    }

    private TestCase createTestCase(
            UUID id,
            boolean sample,
            String input,
            String expectedOutput
    ) {
        TestCase testCase = new TestCase();

        testCase.setId(id);
        testCase.setQuestionId(questionId);
        testCase.setInput(input);
        testCase.setExpectedOutput(expectedOutput);
        testCase.setSample(sample);
        testCase.setWeight(1);
        testCase.setOrderIndex(sample ? 1 : 2);

        return testCase;
    }
}