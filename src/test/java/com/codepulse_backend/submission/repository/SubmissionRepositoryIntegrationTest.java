package com.codepulse_backend.submission.repository;

import com.codepulse_backend.auth.security.CustomUserDetails;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.common.enums.TestCaseResultStatus;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.session.SessionStatus;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;
import com.codepulse_backend.submission.service.SubmissionPersistenceService;
import com.codepulse_backend.testcase.entity.TestCase;
import com.codepulse_backend.user.User;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.TestTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class SubmissionRepositoryIntegrationTest {

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SubmissionPersistenceService submissionPersistenceService;

    @Autowired
    private JdbcTemplate jdbc;

    // Set only by tests that commit their setup data; removed in deleteCommittedData().
    private UUID committedContestId;
    private UUID committedCandidateId;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterTransaction
    void deleteCommittedData() {
        if (committedContestId == null) {
            return;
        }
        UUID id = committedContestId;
        jdbc.update("DELETE FROM result_question_scores WHERE result_id IN (SELECT id FROM results WHERE contest_id = ?)", id);
        jdbc.update("DELETE FROM results WHERE contest_id = ?", id);
        jdbc.update("DELETE FROM manual_evaluations WHERE session_id IN (SELECT id FROM assessment_sessions WHERE contest_id = ?)", id);
        jdbc.update("DELETE FROM submission_test_case_results WHERE submission_id IN (SELECT s.id FROM submissions s JOIN assessment_sessions a ON a.id = s.session_id WHERE a.contest_id = ?)", id);
        jdbc.update("DELETE FROM submissions WHERE session_id IN (SELECT id FROM assessment_sessions WHERE contest_id = ?)", id);
        jdbc.update("DELETE FROM assessment_sessions WHERE contest_id = ?", id);
        jdbc.update("DELETE FROM contests WHERE id = ?", id);
        jdbc.update("DELETE FROM audit_logs WHERE actor_id = ?", committedCandidateId);
        jdbc.update("DELETE FROM users WHERE id = ?", committedCandidateId);
        committedContestId = null;
        committedCandidateId = null;
    }

    @Test
    void countedSubmissionShouldChooseBestScoreAndEarliestTie() {

        Instant now = Instant.now();

        // ---------------------------------------------------------
        // Candidate
        // ---------------------------------------------------------
        User candidate = User.builder()
                .email("submission-test-" + System.nanoTime() + "@example.com")
                .passwordHash("test")
                .fullName("Submission Test Candidate")
                .role(Role.CANDIDATE)
                .isActive(true)
                .build();

        entityManager.persist(candidate);
        entityManager.flush();

        authenticate(candidate);

        // ---------------------------------------------------------
        // Contest
        // ---------------------------------------------------------
        Contest contest = Contest.builder()
                .title("Submission Repository Test Contest")
                .description("Integration test")
                .startTime(now.minusSeconds(3600))
                .endTime(now.plusSeconds(3600))
                .durationMinutes(60)
                .allowedLanguages(List.of("PYTHON"))
                .status(ContestStatus.ONGOING)
                .build();

        contest.setCreatedBy(candidate.getId());
        contest.setUpdatedBy(candidate.getId());

        entityManager.persist(contest);
        entityManager.flush();

        // ---------------------------------------------------------
        // Question
        // ---------------------------------------------------------
        Question question = Question.builder()
                .contestId(contest.getId())
                .title("Test Question")
                .description("Repository integration test")
                .difficulty(Difficulty.EASY)
                .points(100)
                .timeLimitMs(2000)
                .memoryLimitKb(262144)
                .orderIndex(1)
                .build();

        question.setCreatedBy(candidate.getId());
        question.setUpdatedBy(candidate.getId());

        entityManager.persist(question);
        entityManager.flush();

        // ---------------------------------------------------------
        // Assessment session
        // ---------------------------------------------------------
        AssessmentSession session = new AssessmentSession();
        session.setContestId(contest.getId());
        session.setCandidateId(candidate.getId());
        session.setStartedAt(now);
        session.setEndsAt(now.plusSeconds(3600));
        session.setStatus(SessionStatus.IN_PROGRESS);
        session.setCreatedBy(candidate.getId());
        session.setUpdatedBy(candidate.getId());

        entityManager.persist(session);
        entityManager.flush();

        // ---------------------------------------------------------
        // Submissions
        // ---------------------------------------------------------
        Submission lowScore = createSubmission(
                session,
                question,
                candidate,
                new BigDecimal("40.00"),
                now.minusSeconds(30)
        );

        Submission highScore = createSubmission(
                session,
                question,
                candidate,
                new BigDecimal("80.00"),
                now.minusSeconds(20)
        );

        Submission tiedEarlier = createSubmission(
                session,
                question,
                candidate,
                new BigDecimal("80.00"),
                now.minusSeconds(40)
        );

        Submission pending = createSubmission(
                session,
                question,
                candidate,
                new BigDecimal("100.00"),
                now.minusSeconds(10)
        );

        pending.setStatus(SubmissionStatus.PENDING);

        Submission systemError = createSubmission(
                session,
                question,
                candidate,
                new BigDecimal("0.00"),
                now.minusSeconds(5)
        );

        systemError.setStatus(SubmissionStatus.SYSTEM_ERROR);

        entityManager.flush();
        entityManager.clear();

        // ---------------------------------------------------------
        // Act
        // ---------------------------------------------------------
        List<Submission> counted =
                submissionRepository.findCountedSubmissions(session.getId());

        // ---------------------------------------------------------
        // Assert
        // ---------------------------------------------------------
        assertEquals(1, counted.size());

        assertEquals(
                tiedEarlier.getId(),
                counted.get(0).getId()
        );

        assertEquals(
                new BigDecimal("80.00"),
                counted.get(0).getScore()
        );
    }

    @Test
    void applyEvaluationTwiceShouldNotCreateDuplicateResults() {

        Instant now = Instant.now();

        // ---------------------------------------------------------
        // Candidate
        // ---------------------------------------------------------
        User candidate = User.builder()
                .email("evaluation-test-" + System.nanoTime() + "@example.com")
                .passwordHash("test")
                .fullName("Evaluation Test Candidate")
                .role(Role.CANDIDATE)
                .isActive(true)
                .build();

        entityManager.persist(candidate);
        entityManager.flush();

        authenticate(candidate);

        // ---------------------------------------------------------
        // Contest
        // ---------------------------------------------------------
        Contest contest = Contest.builder()
                .title("Evaluation Test Contest")
                .description("Integration test")
                .startTime(now.minusSeconds(3600))
                .endTime(now.plusSeconds(3600))
                .durationMinutes(60)
                .allowedLanguages(List.of("PYTHON"))
                .status(ContestStatus.ONGOING)
                .build();

        contest.setCreatedBy(candidate.getId());
        contest.setUpdatedBy(candidate.getId());

        entityManager.persist(contest);
        entityManager.flush();

        // ---------------------------------------------------------
        // Question
        // ---------------------------------------------------------
        Question question = Question.builder()
                .contestId(contest.getId())
                .title("Evaluation Test Question")
                .description("Integration test")
                .difficulty(Difficulty.EASY)
                .points(100)
                .timeLimitMs(2000)
                .memoryLimitKb(262144)
                .orderIndex(1)
                .build();

        question.setCreatedBy(candidate.getId());
        question.setUpdatedBy(candidate.getId());

        entityManager.persist(question);
        entityManager.flush();

        // ---------------------------------------------------------
        // Assessment session
        // ---------------------------------------------------------
        AssessmentSession session = new AssessmentSession();
        session.setContestId(contest.getId());
        session.setCandidateId(candidate.getId());
        session.setStartedAt(now);
        session.setEndsAt(now.plusSeconds(3600));
        session.setStatus(SessionStatus.IN_PROGRESS);
        session.setCreatedBy(candidate.getId());
        session.setUpdatedBy(candidate.getId());

        entityManager.persist(session);
        entityManager.flush();

        // ---------------------------------------------------------
        // Test case
        // IMPORTANT: must be committed before REQUIRES_NEW evaluation
        // ---------------------------------------------------------
        TestCase testCase = new TestCase();
        testCase.setQuestionId(question.getId());
        testCase.setInput("2 3");
        testCase.setExpectedOutput("5");
        testCase.setSample(true);
        testCase.setWeight(1);
        testCase.setOrderIndex(1);
        testCase.setCreatedBy(candidate.getId());
        testCase.setUpdatedBy(candidate.getId());

        entityManager.persist(testCase);
        entityManager.flush();

        UUID testCaseId = testCase.getId();

        // ---------------------------------------------------------
        // Pending submission
        // ---------------------------------------------------------
        Submission submission = new Submission();
        submission.setSessionId(session.getId());
        submission.setQuestionId(question.getId());
        submission.setCandidateId(candidate.getId());
        submission.setLanguage("PYTHON");
        submission.setSourceCode("print('test')");
        submission.setSubmissionType(SubmissionType.SUBMIT);
        submission.setStatus(SubmissionStatus.PENDING);
        submission.setPassedCount(0);
        submission.setTotalCount(1);
        submission.setSubmittedAt(now);
        submission.setQueuedAt(now);
        submission.setQueueAttempts(1);
        submission.setCreatedBy(candidate.getId());
        submission.setUpdatedBy(candidate.getId());

        entityManager.persist(submission);
        entityManager.flush();

        UUID submissionId = submission.getId();

        // ---------------------------------------------------------
        // Commit all setup data
        // REQUIRES_NEW must be able to see it
        // ---------------------------------------------------------
        committedContestId = contest.getId();
        committedCandidateId = candidate.getId();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        // ---------------------------------------------------------
        // First evaluation
        // ---------------------------------------------------------
        SubmissionTestCaseResult firstResult =
                createResult(testCaseId);

        submissionPersistenceService.applyEvaluation(
                submissionId,
                List.of(firstResult),
                new BigDecimal("100.00"),
                SubmissionStatus.ACCEPTED,
                1,
                1,
                null,
                now
        );

        // ---------------------------------------------------------
        // Start a fresh test transaction
        // ---------------------------------------------------------
        TestTransaction.start();

        entityManager.clear();

        // ---------------------------------------------------------
        // Second evaluation with the same event
        // ---------------------------------------------------------
        SubmissionTestCaseResult secondResult =
                createResult(testCaseId);

        submissionPersistenceService.applyEvaluation(
                submissionId,
                List.of(secondResult),
                new BigDecimal("100.00"),
                SubmissionStatus.ACCEPTED,
                1,
                1,
                null,
                now
        );

        entityManager.flush();
        entityManager.clear();

        // ---------------------------------------------------------
        // Verify submission finalized once
        // ---------------------------------------------------------
        Submission savedSubmission =
                entityManager.find(
                        Submission.class,
                        submissionId
                );

        assertEquals(
                SubmissionStatus.ACCEPTED,
                savedSubmission.getStatus()
        );

        // ---------------------------------------------------------
        // Verify exactly one result exists
        // ---------------------------------------------------------
        Long resultCount = entityManager.createQuery(
                        """
                        select count(r)
                        from SubmissionTestCaseResult r
                        where r.submission.id = :submissionId
                        """,
                        Long.class
                )
                .setParameter("submissionId", submissionId)
                .getSingleResult();

        assertEquals(1L, resultCount);
    }

    private void authenticate(User candidate) {

        CustomUserDetails userDetails =
                new CustomUserDetails(
                        candidate.getId(),
                        candidate.getEmail(),
                        candidate.getPasswordHash(),
                        true,
                        true,
                        true,
                        true,
                        Collections.emptyList()
                );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                )
        );
    }

    private SubmissionTestCaseResult createResult(UUID testCaseId) {

        SubmissionTestCaseResult result =
                new SubmissionTestCaseResult();

        result.setTestCaseId(testCaseId);
        result.setStatus(TestCaseResultStatus.PASSED);
        result.setActualOutput("5");
        result.setStderr("");
        result.setExecutionTimeMs(
                new BigDecimal("10.00")
        );
        result.setMemoryUsedKb(1000);
        result.setWeight(1);
        result.setSample(true);

        return result;
    }

    private Submission createSubmission(
            AssessmentSession session,
            Question question,
            User candidate,
            BigDecimal score,
            Instant submittedAt
    ) {

        Submission submission = new Submission();

        submission.setSessionId(session.getId());
        submission.setQuestionId(question.getId());
        submission.setCandidateId(candidate.getId());

        submission.setLanguage("PYTHON");
        submission.setSourceCode("print('test')");

        submission.setSubmissionType(SubmissionType.SUBMIT);
        submission.setStatus(SubmissionStatus.ACCEPTED);

        submission.setScore(score);
        submission.setPassedCount(1);
        submission.setTotalCount(1);

        submission.setSubmittedAt(submittedAt);
        submission.setEvaluatedAt(submittedAt);

        submission.setQueueAttempts(1);

        submission.setCreatedBy(candidate.getId());
        submission.setUpdatedBy(candidate.getId());

        entityManager.persist(submission);

        return submission;
    }
}