package com.codepulse_backend.testcase.service;

import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.exception.AccessDeniedException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.testcase.dto.CreateTestCaseRequest;
import com.codepulse_backend.testcase.dto.TestCaseAdminResponse;
import com.codepulse_backend.testcase.dto.TestCaseSampleResponse;
import com.codepulse_backend.testcase.entity.TestCase;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestCaseService {

    private final TestCaseRepository testCaseRepository;
    private final QuestionRepository questionRepository;
    private final ContestRepository contestRepository;
    private final ContestCandidateRepository contestCandidateRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    // ─── Create ───────────────────────────────────────────────────────────────

    @Transactional
    public TestCaseAdminResponse createTestCase(
            UUID questionId,
            CreateTestCaseRequest request) {

        if (!questionRepository.existsById(questionId)) {
            throw new ResourceNotFoundException(
                    "Question not found with id: " + questionId
            );
        }

        int orderIndex = (int) testCaseRepository.countByQuestionId(questionId) + 1;

        TestCase testCase = TestCase.builder()
                .questionId(questionId)
                .input(request.input())
                .expectedOutput(request.expectedOutput())
                .isSample(request.isSample())
                .weight(request.weight())
                .orderIndex(orderIndex)
                .build();

        TestCase saved = testCaseRepository.save(testCase);

        User currentUser = getCurrentUser();
        auditService.log(
                currentUser.getId(),
                "TEST_CASE_CREATED",
                "TEST_CASE",
                saved.getId(),
                "Admin created test case for question " + questionId
        );

        log.info(
                "Test case {} created for question {} by {}",
                saved.getId(),
                questionId,
                currentUser.getEmail()
        );

        return toAdminResponse(saved);
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    @Transactional
    public void deleteTestCase(UUID testCaseId) {
        TestCase testCase = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Test case not found with id: " + testCaseId
                ));

        User currentUser = getCurrentUser();

        auditService.log(
                currentUser.getId(),
                "TEST_CASE_DELETED",
                "TEST_CASE",
                testCase.getId(),
                "Admin deleted test case for question " + testCase.getQuestionId()
        );

        testCaseRepository.delete(testCase);

        log.info(
                "Test case {} deleted by {}",
                testCaseId,
                currentUser.getEmail()
        );
    }

    // ─── Role-dependent List ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Object getTestCasesForQuestion(UUID questionId) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Question not found with id: " + questionId
                ));

        User currentUser = getCurrentUser();

        if (currentUser.getRole() == Role.CANDIDATE) {
            UUID contestId = question.getContestId();

            if (!contestCandidateRepository.existsByContestIdAndCandidateId(
                    contestId,
                    currentUser.getId())) {
                throw new AccessDeniedException(
                        "You are not assigned to this contest"
                );
            }

            Contest contest = contestRepository.findById(contestId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Contest not found with id: " + contestId
                    ));

            if (contest.getStatus() != ContestStatus.ONGOING) {
                throw new AccessDeniedException(
                        "Test cases are only accessible when the contest is ongoing"
                );
            }

            // Candidate path: fetch only sample cases from the database.
            return testCaseRepository
                    .findByQuestionIdAndIsSampleTrueOrderByOrderIndexAsc(questionId)
                    .stream()
                    .map(this::toSampleResponse)
                    .toList();
        }

        // Admin / Evaluator path: full test-case data.
        return testCaseRepository
                .findByQuestionIdOrderByOrderIndexAsc(questionId)
                .stream()
                .map(this::toAdminResponse)
                .toList();
    }

    // ─── Embedding Helpers for QuestionService ────────────────────────────────

    @Transactional(readOnly = true)
    public List<TestCaseAdminResponse> getAllTestCasesForEmbedding(
            UUID questionId) {

        return testCaseRepository
                .findByQuestionIdOrderByOrderIndexAsc(questionId)
                .stream()
                .map(this::toAdminResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TestCaseSampleResponse> getSampleTestCasesForEmbedding(
            UUID questionId) {

        return testCaseRepository
                .findByQuestionIdAndIsSampleTrueOrderByOrderIndexAsc(questionId)
                .stream()
                .map(this::toSampleResponse)
                .toList();
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private TestCaseAdminResponse toAdminResponse(TestCase testCase) {
        return new TestCaseAdminResponse(
                testCase.getId(),
                testCase.getInput(),
                testCase.getExpectedOutput(),
                testCase.isSample(),
                testCase.getWeight(),
                testCase.getOrderIndex()
        );
    }

    private TestCaseSampleResponse toSampleResponse(TestCase testCase) {
        return new TestCaseSampleResponse(
                testCase.getId(),
                testCase.getInput(),
                testCase.getOrderIndex()
        );
    }

    // ─── Current User Helper ──────────────────────────────────────────────────

    private User getCurrentUser() {
        Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();

        String email = auth.getName();

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Authenticated user not found"
                ));
    }
}