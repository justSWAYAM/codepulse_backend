package com.codepulse_backend.question.service;

import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.exception.AccessDeniedException;
import com.codepulse_backend.common.exception.InvalidStateException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.question.dto.*;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import com.codepulse_backend.testcase.dto.TestCaseAdminResponse;
import com.codepulse_backend.testcase.dto.TestCaseSampleResponse;
import com.codepulse_backend.testcase.service.TestCaseService;
@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionService {

    private final QuestionRepository questionRepository;
    private final ContestRepository contestRepository;
    private final ContestCandidateRepository contestCandidateRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final TestCaseService testCaseService;
    private final com.codepulse_backend.session.SessionService sessionService;

    // ─── Create ───────────────────────────────────────────────────────────────

    @Transactional
    public QuestionAdminResponse createQuestion(UUID contestId, CreateQuestionRequest request) {
        assertContestEditable(contestId);

        // Compute next order index (append to end)
        int orderIndex = questionRepository.findMaxOrderIndexByContestId(contestId) + 1;

        Question question = Question.builder()
                .contestId(contestId)
                .title(request.title())
                .description(request.description())
                .difficulty(request.difficulty())
                .points(request.points())
                .timeLimitMs(request.timeLimitMs())
                .memoryLimitKb(request.memoryLimitKb())
                .orderIndex(orderIndex)
                .build();

        Question saved = questionRepository.save(question);

        // Same transaction as the question: if any test case fails, nothing is created
        if (request.testCases() != null) {
            request.testCases().forEach(tc -> testCaseService.createTestCase(saved.getId(), tc));
        }

        User currentUser = getCurrentUser();
        auditService.log(currentUser.getId(), "QUESTION_CREATED", "QUESTION", saved.getId(),
                "Admin created question: '" + saved.getTitle() + "' in contest " + contestId);

        log.info("Question {} created in contest {} by {}", saved.getId(), contestId, currentUser.getEmail());
        return toAdminResponse(saved);
    }

    // ─── Update ───────────────────────────────────────────────────────────────

    @Transactional
    public QuestionAdminResponse updateQuestion(UUID contestId, UUID questionId,
                                                UpdateQuestionRequest request) {
        assertContestEditable(contestId);
        Question question = findQuestionInContest(contestId, questionId);

        if (request.title() != null)           question.setTitle(request.title());
        if (request.description() != null)     question.setDescription(request.description());
        if (request.difficulty() != null)      question.setDifficulty(request.difficulty());
        if (request.points() != null)          question.setPoints(request.points());
        if (request.timeLimitMs() != null)     question.setTimeLimitMs(request.timeLimitMs());
        if (request.memoryLimitKb() != null)   question.setMemoryLimitKb(request.memoryLimitKb());

        return toAdminResponse(questionRepository.save(question));
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    @Transactional
    public void deleteQuestion(UUID contestId, UUID questionId) {
        assertContestEditable(contestId);
        Question question = findQuestionInContest(contestId, questionId);
        questionRepository.delete(question);
        log.info("Question {} deleted from contest {}", questionId, contestId);
    }

    /**
     * Questions and test cases are frozen once a contest starts: changing points,
     * limits or test cases mid-exam would score candidates against different rules.
     */
    public void assertContestEditable(UUID contestId) {
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("Contest not found with id: " + contestId));

        if (contest.getStatus() == ContestStatus.ONGOING || contest.getStatus() == ContestStatus.COMPLETED) {
            throw new InvalidStateException(
                    "Questions and test cases cannot be changed once a contest is " + contest.getStatus());
        }
    }

    // ─── List ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<?> getQuestions(UUID contestId) {
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("Contest not found with id: " + contestId));

        User currentUser = getCurrentUser();
        List<Question> questions = questionRepository.findAllByContestIdOrderByOrderIndex(contestId);

        if (currentUser.getRole() == Role.CANDIDATE) {
            // Candidate must be assigned to this contest
            if (!contestCandidateRepository.existsByContestIdAndCandidateId(contestId, currentUser.getId())) {
                throw new AccessDeniedException("You are not assigned to this contest");
            }
            // Candidate can only view questions when the contest is ONGOING
            if (contest.getStatus() != ContestStatus.ONGOING) {
                throw new AccessDeniedException("Questions are only accessible when the contest is ongoing");
            }
            sessionService.requireActiveSession(contestId, currentUser.getId());
            return questions.stream().map(this::toCandidateResponse).toList();
        }

        // Admin / Evaluator — full view
        return questions.stream().map(this::toAdminResponse).toList();
    }

    // ─── Detail ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Object getQuestionDetail(UUID contestId, UUID questionId) {
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("Contest not found with id: " + contestId));

        Question question = findQuestionInContest(contestId, questionId);
        User currentUser = getCurrentUser();

        if (currentUser.getRole() == Role.CANDIDATE) {
            if (!contestCandidateRepository.existsByContestIdAndCandidateId(contestId, currentUser.getId())) {
                throw new AccessDeniedException("You are not assigned to this contest");
            }
            if (contest.getStatus() != ContestStatus.ONGOING) {
                throw new AccessDeniedException("Questions are only accessible when the contest is ongoing");
            }
            sessionService.requireActiveSession(contestId, currentUser.getId());
            return toCandidateResponse(question);
        }

        return toAdminResponse(question);
    }

    // ─── Reorder ──────────────────────────────────────────────────────────────

    @Transactional
    public List<QuestionAdminResponse> reorderQuestions(UUID contestId,
                                                         ReorderQuestionsRequest request) {
        assertContestEditable(contestId);

        List<Question> questions = questionRepository
                .findAllByIdInAndContestId(request.orderedIds(), contestId);

        if (questions.size() != request.orderedIds().size()) {
            throw new InvalidStateException(
                    "One or more question IDs do not belong to contest " + contestId
                            + ". Expected " + request.orderedIds().size()
                            + " questions, found " + questions.size());
        }

        // A partial list would assign 1..k and collide with the questions left out
        long total = questionRepository.countByContestId(contestId);
        if (total != request.orderedIds().size()) {
            throw new InvalidStateException(
                    "Reorder must include all " + total + " questions of the contest, got "
                            + request.orderedIds().size());
        }

        // Build a map for O(1) lookup, then assign order by list position
        Map<UUID, Question> qMap = new HashMap<>();
        questions.forEach(q -> qMap.put(q.getId(), q));

        List<Question> reordered = new ArrayList<>();
        for (UUID id : request.orderedIds()) {
            reordered.add(qMap.get(id));
        }

        // Two-phase update: the unique (contest_id, order_index) index is checked per row,
        // so moving straight to the final values (e.g. swapping 1 and 2) would collide.
        // Park every row on a unique negative index first, flush, then assign the real ones.
        for (int i = 0; i < reordered.size(); i++) {
            reordered.get(i).setOrderIndex(-(i + 1));
        }
        questionRepository.saveAllAndFlush(reordered);

        for (int i = 0; i < reordered.size(); i++) {
            reordered.get(i).setOrderIndex(i + 1);  // 1-based
        }

        return questionRepository.saveAllAndFlush(reordered).stream()
                .sorted(Comparator.comparingInt(Question::getOrderIndex))
                .map(this::toAdminResponse)
                .toList();
    }

    // ─── Private Mappers ──────────────────────────────────────────────────────

    private QuestionAdminResponse toAdminResponse(Question q) {
        List<TestCaseAdminResponse> testCases =
                testCaseService.getAllTestCasesForEmbedding(q.getId());

        return new QuestionAdminResponse(
                q.getId(),
                q.getContestId(),
                q.getTitle(),
                q.getDescription(),
                q.getDifficulty(),
                q.getPoints(),
                q.getTimeLimitMs(),
                q.getMemoryLimitKb(),
                q.getOrderIndex(),
                q.getCreatedAt(),
                q.getCreatedBy(),
                testCases
        );
    }

    private QuestionCandidateResponse toCandidateResponse(Question q) {
        List<TestCaseSampleResponse> sampleTestCases =
                testCaseService.getSampleTestCasesForEmbedding(q.getId());

        return new QuestionCandidateResponse(
                q.getId(),
                q.getContestId(),
                q.getTitle(),
                q.getDescription(),
                q.getDifficulty(),
                q.getPoints(),
                q.getTimeLimitMs(),
                q.getMemoryLimitKb(),
                q.getOrderIndex(),
                sampleTestCases
        );
    }

    // ─── Private Helpers ──────────────────────────────────────────────────────

    /**
     * Finds a question by ID and validates it belongs to the given contest.
     * Prevents cross-contest question mutations.
     */
    private Question findQuestionInContest(UUID contestId, UUID questionId) {
        Question q = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question not found with id: " + questionId));
        if (!q.getContestId().equals(contestId)) {
            throw new ResourceNotFoundException(
                    "Question " + questionId + " does not belong to contest " + contestId);
        }
        return q;
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
    }
}
