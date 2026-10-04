package com.codepulse_backend.question.service;

import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.exception.AccessDeniedException;
import com.codepulse_backend.common.exception.DuplicateResourceException;
import com.codepulse_backend.common.exception.InvalidStateException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.common.dto.PagedResponse;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.question.dto.*;
import com.codepulse_backend.question.entity.McqOption;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.entity.Subject;
import com.codepulse_backend.question.repository.McqOptionRepository;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.question.repository.SubjectRepository;
import com.codepulse_backend.testcase.entity.TestCase;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Core service for Module 5A – Question Library.
 *
 * Responsibilities:
 * <ol>
 *   <li>Create, read, update, delete library questions (contest_id IS NULL).</li>
 *   <li>Browse library questions with subject/type filters (paged).</li>
 *   <li>Add questions from the library to a contest via a deep-copy transaction.</li>
 * </ol>
 *
 * Deep-copy invariant: editing or deleting a library question after it has been
 * copied into a contest has zero effect on the contest copy, because the copy
 * is an independent {@code questions} row with its own test cases and MCQ options.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionLibraryService {

    private final QuestionRepository questionRepository;
    private final SubjectRepository subjectRepository;
    private final TestCaseRepository testCaseRepository;
    private final McqOptionRepository mcqOptionRepository;
    private final ContestRepository contestRepository;
    private final UserRepository userRepository;

    // ─── Browse library ───────────────────────────────────────────────────────

    /**
     * Paged list of library questions, optionally filtered by subject and/or type.
     * A null filter param means "no filter on that dimension".
     * Includes author name and a hasNoTestCases flag for the UI badge.
     */
    @Transactional(readOnly = true)
    public PagedResponse<LibraryQuestionResponse> getLibraryQuestions(
            UUID subjectId,
            QuestionType type,
            Pageable pageable) {

        Page<Question> page = questionRepository.findLibraryQuestions(subjectId, type, pageable);

        Page<LibraryQuestionResponse> mapped = page.map(q -> {
            // Resolve author full name for display (never exposes email / UUID to FE)
            String authorName = (q.getCreatedBy() != null)
                    ? userRepository.findById(q.getCreatedBy())
                            .map(User::getFullName)
                            .orElse("Unknown")
                    : "Unknown";

            long testCaseCount = testCaseRepository.countByQuestionId(q.getId());
            boolean hasNoTestCases = (q.getQuestionType() == QuestionType.DSA
                    || q.getQuestionType() == QuestionType.SQL)
                    && testCaseCount == 0;

            return new LibraryQuestionResponse(
                    q.getId(),
                    q.getTitle(),
                    q.getQuestionType(),
                    q.getDifficulty(),
                    q.getPoints(),
                    q.getSubject() != null ? q.getSubject().getId() : null,
                    q.getSubject() != null ? q.getSubject().getName() : null,
                    authorName,
                    q.getCreatedAt(),
                    hasNoTestCases,
                    q.getSourceQuestion() != null ? q.getSourceQuestion().getId() : null
            );
        });

        return PagedResponse.from(mapped);
    }

    // ─── Create library question ───────────────────────────────────────────────

    /**
     * Creates a library question (contest_id = NULL) with optional MCQ options.
     * Test cases are added separately via the existing TestCase endpoints,
     * which already handle library questions after the migration makes contestId nullable.
     */
    @Transactional
    public LibraryQuestionResponse createLibraryQuestion(CreateLibraryQuestionRequest request) {
        User currentUser = getCurrentUser();
        assertAdminOrEvaluator(currentUser);

        Subject subject = subjectRepository.findById(request.subjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subject not found with id: " + request.subjectId()));

        Question question = Question.builder()
                .contestId(null)          // NULL = library question
                .subject(subject)
                .questionType(request.questionType())
                .title(request.title())
                .description(request.description())
                .difficulty(request.difficulty())
                .points(request.points())
                .timeLimitMs(request.timeLimitMs() != null ? request.timeLimitMs() : 2000)
                .memoryLimitKb(request.memoryLimitKb() != null ? request.memoryLimitKb() : 262144)
                .orderIndex(0)            // unused for library questions
                .schemaSql(request.schemaSql())
                .orderMatters(request.orderMatters())
                .modelAnswer(request.modelAnswer())
                .build();

        Question saved = questionRepository.save(question);

        // Persist MCQ options if present
        if (request.questionType() == QuestionType.MCQ && request.options() != null) {
            int idx = 1;
            for (McqOptionRequest optReq : request.options()) {
                McqOption opt = McqOption.builder()
                        .questionId(saved.getId())
                        .text(optReq.text())
                        .isCorrect(optReq.isCorrect())
                        .orderIndex(idx++)
                        .build();
                mcqOptionRepository.save(opt);
            }
        }

        log.info("Library question '{}' ({}) created in subject '{}' by {}",
                saved.getTitle(), saved.getQuestionType(),
                subject.getName(), currentUser.getEmail());

        return toLibraryResponse(saved);
    }

    // ─── Update library question ───────────────────────────────────────────────

    /**
     * Updates a library question. Only the author or an Admin may edit.
     * If the question has already been copied into any contest, those copies
     * remain unchanged — the isolation guarantee holds.
     */
    @Transactional
    public LibraryQuestionResponse updateLibraryQuestion(UUID questionId,
                                                          UpdateLibraryQuestionRequest request) {
        Question question = getLibraryQuestionById(questionId);
        User currentUser = getCurrentUser();
        assertCanEdit(currentUser, question);

        if (request.title() != null)        question.setTitle(request.title());
        if (request.description() != null)  question.setDescription(request.description());
        if (request.difficulty() != null)   question.setDifficulty(request.difficulty());
        if (request.points() != null)       question.setPoints(request.points());
        if (request.timeLimitMs() != null)  question.setTimeLimitMs(request.timeLimitMs());
        if (request.memoryLimitKb() != null) question.setMemoryLimitKb(request.memoryLimitKb());
        if (request.schemaSql() != null)    question.setSchemaSql(request.schemaSql());
        if (request.orderMatters() != null) question.setOrderMatters(request.orderMatters());
        if (request.modelAnswer() != null)  question.setModelAnswer(request.modelAnswer());

        // Subject change
        if (request.subjectId() != null) {
            Subject newSubject = subjectRepository.findById(request.subjectId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Subject not found with id: " + request.subjectId()));
            question.setSubject(newSubject);
        }

        Question saved = questionRepository.save(question);
        log.info("Library question {} updated by {}", questionId, currentUser.getEmail());
        return toLibraryResponse(saved);
    }

    // ─── Delete library question ───────────────────────────────────────────────

    /**
     * Deletes a library question. Only the author or an Admin may delete.
     * source_question_id ON DELETE SET NULL means contest copies are unaffected.
     */
    @Transactional
    public void deleteLibraryQuestion(UUID questionId) {
        Question question = getLibraryQuestionById(questionId);
        User currentUser = getCurrentUser();
        assertCanEdit(currentUser, question);

        questionRepository.delete(question);
        log.info("Library question {} deleted by {}", questionId, currentUser.getEmail());
    }

    // ─── Deep-copy: Add to contest ────────────────────────────────────────────

    /**
     * Deep-copies the selected library questions into the target contest.
     * Each copy is a fully independent row with its own test cases and MCQ options.
     * Questions already copied into this contest are silently skipped (idempotent).
     *
     * The entire operation runs in a single transaction: either all copies succeed,
     * or none of them are persisted.
     *
     * @param contestId   the target contest to copy questions into
     * @param questionIds list of library question IDs to copy
     * @return the number of new copies actually created (skipped = already present)
     */
    @Transactional
    public int addQuestionsToContest(UUID contestId, List<UUID> questionIds) {
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Contest not found with id: " + contestId));

        if (contest.getStatus() == ContestStatus.ONGOING
                || contest.getStatus() == ContestStatus.COMPLETED) {
            throw new InvalidStateException(
                    "Cannot add questions to a contest that is " + contest.getStatus());
        }

        List<Question> sources = questionRepository.findAllById(questionIds);

        // Validate all requested IDs are library questions
        for (Question src : sources) {
            if (src.getContestId() != null) {
                throw new InvalidStateException(
                        "Question " + src.getId() + " is not a library question " +
                        "(it belongs to contest " + src.getContestId() + ")");
            }
        }

        int nextOrderIndex = questionRepository.findMaxOrderIndexByContestId(contestId);
        int copiedCount = 0;

        for (Question source : sources) {
            // Idempotency: skip if already copied
            if (questionRepository.existsByContestIdAndSourceQuestionId(contestId, source.getId())) {
                log.debug("Skipping duplicate copy of library question {} into contest {}",
                        source.getId(), contestId);
                continue;
            }

            nextOrderIndex++;

            // ── Deep copy: Question ──────────────────────────────────────────
            Question copy = Question.builder()
                    .contestId(contestId)
                    .subject(source.getSubject())
                    .sourceQuestion(source)
                    .questionType(source.getQuestionType())
                    .title(source.getTitle())
                    .description(source.getDescription())
                    .difficulty(source.getDifficulty())
                    .points(source.getPoints())
                    .timeLimitMs(source.getTimeLimitMs())
                    .memoryLimitKb(source.getMemoryLimitKb())
                    .orderIndex(nextOrderIndex)
                    .schemaSql(source.getSchemaSql())
                    .orderMatters(source.getOrderMatters())
                    .modelAnswer(source.getModelAnswer())
                    .build();

            Question savedCopy = questionRepository.save(copy);

            // ── Deep copy: Test cases (DSA / SQL) ────────────────────────────
            List<TestCase> testCases =
                    testCaseRepository.findByQuestionIdOrderByOrderIndexAsc(source.getId());

            for (TestCase tc : testCases) {
                TestCase tcCopy = TestCase.builder()
                        .questionId(savedCopy.getId())
                        .input(tc.getInput())
                        .expectedOutput(tc.getExpectedOutput())
                        .isSample(tc.isSample())
                        .weight(tc.getWeight())
                        .orderIndex(tc.getOrderIndex())
                        .build();
                testCaseRepository.save(tcCopy);
            }

            // ── Deep copy: MCQ options ────────────────────────────────────────
            if (source.getQuestionType() == QuestionType.MCQ) {
                List<McqOption> options =
                        mcqOptionRepository.findByQuestionIdOrderByOrderIndexAsc(source.getId());

                for (McqOption opt : options) {
                    McqOption optCopy = McqOption.builder()
                            .questionId(savedCopy.getId())
                            .text(opt.getText())
                            .isCorrect(opt.isCorrect())
                            .orderIndex(opt.getOrderIndex())
                            .build();
                    mcqOptionRepository.save(optCopy);
                }
            }

            copiedCount++;
            log.info("Library question {} deep-copied to contest {} as question {} (order {})",
                    source.getId(), contestId, savedCopy.getId(), nextOrderIndex);
        }

        return copiedCount;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /** Fetches a library question (contest_id IS NULL) by ID. */
    private Question getLibraryQuestionById(UUID questionId) {
        Question q = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Library question not found with id: " + questionId));
        if (q.getContestId() != null) {
            throw new ResourceNotFoundException(
                    "Question " + questionId + " is not a library question");
        }
        return q;
    }

    /**
     * Edit/delete permission: author or Admin.
     * Evaluator B cannot edit Evaluator A's question.
     * If createdBy is null (e.g. seeded directly), only Admin can edit.
     */
    private void assertCanEdit(User currentUser, Question question) {
        boolean isAdmin = currentUser.getRole() == Role.ADMIN;
        boolean isAuthor = question.getCreatedBy() != null
                && question.getCreatedBy().equals(currentUser.getId());
        if (!isAdmin && !isAuthor) {
            throw new AccessDeniedException(
                    "Only the author or an Admin can edit/delete this library question");
        }
    }

    private void assertAdminOrEvaluator(User user) {
        if (user.getRole() == Role.CANDIDATE) {
            throw new AccessDeniedException(
                    "Candidates cannot access the question library");
        }
    }

    private LibraryQuestionResponse toLibraryResponse(Question q) {
        String authorName = (q.getCreatedBy() != null)
                ? userRepository.findById(q.getCreatedBy())
                        .map(User::getFullName)
                        .orElse("Unknown")
                : "Unknown";

        long testCaseCount = testCaseRepository.countByQuestionId(q.getId());
        boolean hasNoTestCases = (q.getQuestionType() == QuestionType.DSA
                || q.getQuestionType() == QuestionType.SQL)
                && testCaseCount == 0;

        return new LibraryQuestionResponse(
                q.getId(),
                q.getTitle(),
                q.getQuestionType(),
                q.getDifficulty(),
                q.getPoints(),
                q.getSubject() != null ? q.getSubject().getId() : null,
                q.getSubject() != null ? q.getSubject().getName() : null,
                authorName,
                q.getCreatedAt(),
                hasNoTestCases,
                q.getSourceQuestion() != null ? q.getSourceQuestion().getId() : null
        );
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
    }
}
