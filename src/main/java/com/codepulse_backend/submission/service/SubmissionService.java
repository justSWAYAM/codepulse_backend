package com.codepulse_backend.submission.service;
import com.codepulse_backend.common.dto.PagedResponse;
import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.exception.BadRequestException;
import com.codepulse_backend.common.exception.ConflictException;
import com.codepulse_backend.common.exception.ExecutionBusyException;
import com.codepulse_backend.common.exception.SubmissionQueueUnavailableException;
import com.codepulse_backend.submission.dto.ContestSubmissionRowResponse;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import org.springframework.security.core.Authentication;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.codepulse_backend.submission.dto.SubmissionSummaryResponse;
import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;
import java.util.List;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import com.codepulse_backend.common.enums.SupportedLanguage;
import com.codepulse_backend.common.exception.InvalidStateException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.config.SubmissionProperties;
import com.codepulse_backend.result.service.ResultVisibilityService;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.execution.CodeExecutionService;
import com.codepulse_backend.execution.SubmissionQueueService;
import com.codepulse_backend.execution.dto.ExecutionRequest;
import com.codepulse_backend.execution.dto.ExecutionResult;
import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.session.AssessmentSessionRepository;
import com.codepulse_backend.session.SessionStatus;
import com.codepulse_backend.submission.dto.RunCodeRequest;
import com.codepulse_backend.submission.dto.SubmitCodeRequest;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;
import com.codepulse_backend.submission.repository.SubmissionTestCaseResultRepository;
import com.codepulse_backend.testcase.entity.TestCase;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SubmissionService {

    private final QuestionRepository questionRepository;
    private final ContestRepository contestRepository;
    private final AssessmentSessionRepository sessionRepository;
    private final TestCaseRepository testCaseRepository;
    private final SubmissionRepository submissionRepository;
    private final SubmissionPersistenceService persistenceService;
    private final SubmissionQueueService queueService;
    private final CodeExecutionService executionService;
    private final ScoringService scoringService;
    private final RunConcurrencyLimiter concurrencyLimiter;
    private final SubmissionProperties properties;
    private final SubmissionTestCaseResultRepository submissionTestCaseResultRepository;
    private final SubmissionMapper submissionMapper;
    private final SubmissionAccessGuard accessGuard;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ResultVisibilityService resultVisibility;

    public SubmissionService(
            QuestionRepository questionRepository,
            ContestRepository contestRepository,
            AssessmentSessionRepository sessionRepository,
            TestCaseRepository testCaseRepository,
            SubmissionRepository submissionRepository,
            SubmissionTestCaseResultRepository submissionTestCaseResultRepository,
            SubmissionPersistenceService persistenceService,
            SubmissionQueueService queueService,
            CodeExecutionService executionService,
            ScoringService scoringService,
            RunConcurrencyLimiter concurrencyLimiter,
            SubmissionProperties properties,
            SubmissionMapper submissionMapper,
            SubmissionAccessGuard accessGuard,
            UserRepository userRepository,
            AuditService auditService,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
            ResultVisibilityService resultVisibility
    ) {
        this.questionRepository = questionRepository;
        this.contestRepository = contestRepository;
        this.sessionRepository = sessionRepository;
        this.testCaseRepository = testCaseRepository;
        this.submissionRepository = submissionRepository;
        this.submissionTestCaseResultRepository = submissionTestCaseResultRepository;
        this.persistenceService = persistenceService;
        this.queueService = queueService;
        this.executionService = executionService;
        this.scoringService = scoringService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.properties = properties;
        this.submissionMapper = submissionMapper;
        this.accessGuard = accessGuard;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.resultVisibility = resultVisibility;
    }

    /**
     * Module 4 confirmed fields:
     * Question.contestId
     * Question.points
     * Question.timeLimitMs
     * Question.memoryLimitKb
     */
    public RunOutcome run(
            UUID candidateId,
            RunCodeRequest request
    ) {
        Context context = validateAndResolveContext(
                candidateId,
                request.questionId(),
                request.language(),
                SubmissionType.RUN
        );

        List<TestCase> testCases =
                testCaseRepository
                        .findByQuestionIdAndIsSampleTrueOrderByOrderIndexAsc(
                                request.questionId()
                        );

        if (testCases.isEmpty()) {
            throw new ConflictException("NO_SAMPLE_TEST_CASES",
                    "This question has no sample test cases to run against");
        }

        if (!concurrencyLimiter.tryAcquire()) {
            throw new ExecutionBusyException("EXECUTION_BUSY");
        }

        Instant submittedAt = Instant.now();

        try {
            List<ScoringService.ScoredResult> scoredResults =
                    new ArrayList<>();

            List<SubmissionTestCaseResult> persistedResults =
                    new ArrayList<>();

            String compileOutput = null;

            for (TestCase testCase : testCases) {

                ExecutionResult result =
                        executionService.execute(
                                new ExecutionRequest(
                                        UUID.randomUUID(),
                                        testCase.getId(),
                                        request.sourceCode(),
                                        request.language(),
                                        testCase.getInput(),
                                        testCase.getExpectedOutput(),
                                        context.question().getTimeLimitMs(),
                                        context.question().getMemoryLimitKb()
                                )
                        );

                scoredResults.add(
                        new ScoringService.ScoredResult(
                                result.status(),
                                testCase.getWeight()
                        )
                );

                if (compileOutput == null &&
                        result.compileOutput() != null) {
                    compileOutput = result.compileOutput();
                }

                SubmissionTestCaseResult testCaseResult =
                        new SubmissionTestCaseResult();

                testCaseResult.setTestCaseId(testCase.getId());
                testCaseResult.setStatus(result.status());
                testCaseResult.setActualOutput(result.stdout());
                testCaseResult.setStderr(result.stderr());
                testCaseResult.setExecutionTimeMs(
                        result.executionTimeMs() == null
                                ? null
                                : BigDecimal.valueOf(result.executionTimeMs())
                );
                testCaseResult.setMemoryUsedKb(
                        result.memoryUsedKb() == null
                                ? null
                                : result.memoryUsedKb().intValue()
                );
                testCaseResult.setWeight(testCase.getWeight());
                testCaseResult.setSample(testCase.isSample());

                persistedResults.add(testCaseResult);

                // Compilation error means remaining test cases
                // must not be executed.
                if (result.status() ==
                        com.codepulse_backend.common.enums.TestCaseResultStatus.COMPILATION_ERROR) {

                    for (int i = scoredResults.size();
                         i < testCases.size();
                         i++) {

                        TestCase remaining = testCases.get(i);

                        scoredResults.add(
                                new ScoringService.ScoredResult(
                                        com.codepulse_backend.common.enums.TestCaseResultStatus.COMPILATION_ERROR,
                                        remaining.getWeight()
                                )
                        );

                        SubmissionTestCaseResult remainingResult =
                                new SubmissionTestCaseResult();

                        remainingResult.setTestCaseId(remaining.getId());
                        remainingResult.setStatus(
                                com.codepulse_backend.common.enums.TestCaseResultStatus.COMPILATION_ERROR
                        );
                        remainingResult.setWeight(remaining.getWeight());
                        remainingResult.setSample(remaining.isSample());

                        persistedResults.add(remainingResult);
                    }

                    break;
                }
            }

            SubmissionStatus status =
                    scoringService.resolveStatus(scoredResults);

            int passedCount = (int) scoredResults.stream()
                    .filter(result ->
                            result.status() ==
                                    com.codepulse_backend.common.enums.TestCaseResultStatus.PASSED
                    )
                    .count();

            Submission saved = persistenceService.saveCompletedRun(
                    context.session().getId(),
                    context.question().getId(),
                    candidateId,
                    request.language(),
                    request.sourceCode(),
                    status,
                    passedCount,
                    testCases.size(),
                    compileOutput,
                    submittedAt,
                    persistedResults
            );

            // Run returns the sample outputs directly; no follow-up GET needed
            return new RunOutcome(saved, submissionMapper.toCandidateView(saved, persistedResults, true));

        } finally {
            concurrencyLimiter.release();
        }
    }

    public record RunOutcome(
            Submission submission,
            com.codepulse_backend.submission.dto.SubmissionCandidateView view
    ) {}

    public Submission submit(
            UUID candidateId,
            SubmitCodeRequest request
    ) {
        Context context = validateAndResolveContext(
                candidateId,
                request.questionId(),
                request.language(),
                SubmissionType.SUBMIT
        );

        List<TestCase> testCases =
                testCaseRepository
                        .findByQuestionIdOrderByOrderIndexAsc(
                                request.questionId()
                        );

        if (testCases.isEmpty()) {
            throw new ConflictException("QUESTION_HAS_NO_TEST_CASES",
                    "This question has no test cases, so it cannot be judged");
        }

        Instant now = Instant.now();

        // Re-checks deadline, PENDING and the cap under the session row lock, so
        // double-clicks and session finalization cannot race the insert
        Submission submission =
                persistenceService.createPendingSubmit(
                        context.session().getId(),
                        context.question().getId(),
                        candidateId,
                        request.language(),
                        request.sourceCode(),
                        testCases.size(),
                        now,
                        properties.getGraceSeconds(),
                        properties.getMaxSubmitPerQuestion()
                );

        List<QueuedSubmissionJob.TestCasePayload> payloads =
                testCases.stream()
                        .map(testCase ->
                                new QueuedSubmissionJob.TestCasePayload(
                                        testCase.getId(),
                                        testCase.getInput(),
                                        testCase.getExpectedOutput(),
                                        context.question().getTimeLimitMs(),
                                        context.question().getMemoryLimitKb()
                                )
                        )
                        .toList();

        QueuedSubmissionJob job =
                new QueuedSubmissionJob(
                        submission.getId(),
                        context.question().getId(),
                        context.session().getId(),
                        request.sourceCode(),
                        request.language(),
                        payloads
                );

        try {
            queueService.push(job);
        } catch (Exception e) {
            persistenceService.markSystemError(submission.getId())
                    .ifPresent(eventPublisher::publishEvent);

            throw new SubmissionQueueUnavailableException(
                    "Submission queue is unavailable"
            );
        }

        return submission;
    }

    private Context validateAndResolveContext(
            UUID candidateId,
            UUID questionId,
            String language,
            SubmissionType type
    ) {
        Question question =
                questionRepository.findById(questionId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Question not found"
                                )
                        );

        Contest contest =
                contestRepository.findById(question.getContestId())
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Contest not found"
                                )
                        );

        AssessmentSession session =
                sessionRepository
                        .findByContestIdAndCandidateId(contest.getId(), candidateId)
                        .filter(s -> acceptsWork(s, properties.getGraceSeconds(), Instant.now()))
                        .orElseThrow(() -> new ConflictException("NO_ACTIVE_SESSION",
                                "No active exam session for this contest"));

        SupportedLanguage supportedLanguage;

        try {
            supportedLanguage =
                    SupportedLanguage.fromName(language);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("LANGUAGE_NOT_ALLOWED");
        }

        boolean languageAllowed =
                contest.getAllowedLanguages()
                        .stream()
                        .anyMatch(allowed ->
                                allowed.equalsIgnoreCase(
                                        supportedLanguage.name()
                                )
                                        || allowed.equalsIgnoreCase(
                                        supportedLanguage.getDisplayName()
                                )
                        );

        if (!languageAllowed) {
            throw new BadRequestException("LANGUAGE_NOT_ALLOWED");
        }

        long count =
                submissionRepository
                        .countBySessionIdAndQuestionIdAndSubmissionType(
                                session.getId(),
                                questionId,
                                type
                        );

        int max =
                type == SubmissionType.RUN
                        ? properties.getMaxRunPerQuestion()
                        : properties.getMaxSubmitPerQuestion();

        if (count >= max) {
            throw new ConflictException("SUBMISSION_LIMIT_REACHED",
                    "Limit of " + max + " reached for this question");
        }

        return new Context(
                question,
                contest,
                session
        );
    }

    /**
     * A session accepts Run/Submit while IN_PROGRESS and up to graceSeconds past endsAt.
     * Auto-submit can finalize the session at endsAt (timer poll or scheduler), so an
     * AUTO_SUBMITTED session still inside the grace window is accepted too; otherwise
     * the grace would depend on when the scheduler happened to run.
     */
    public static boolean acceptsWork(AssessmentSession session, int graceSeconds, Instant now) {
        boolean open = session.getStatus() == SessionStatus.IN_PROGRESS
                || session.getStatus() == SessionStatus.AUTO_SUBMITTED;
        return open && !now.isAfter(session.getEndsAt().plusSeconds(graceSeconds));
    }

    private record Context(
            Question question,
            Contest contest,
            AssessmentSession session
    ) {
    }
    /**
     * Candidates get their own submission as a candidate view: sample results in full,
     * hidden test cases only as a passed/total summary. Evaluators and admins get the
     * full evaluator view. A candidate asking for someone else's submission gets 404,
     * so submission IDs can't be probed.
     */
    public Object getSubmission(
            Authentication authentication,
            UUID viewerId,
            UUID submissionId
    ) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("SUBMISSION_NOT_FOUND")
                );

        List<SubmissionTestCaseResult> results =
                submissionTestCaseResultRepository.findBySubmissionId(submissionId);

        if (accessGuard.resolveViewerRole(authentication)
                != SubmissionAccessGuard.ViewerRole.CANDIDATE) {
            return submissionMapper.toEvaluatorView(submission, results);
        }

        accessGuard.assertCandidateOwns(submission, viewerId);

        // Plan 2.10: verdicts are visible while the exam runs; after it ends they stay
        // hidden until Module 9 publishes results.
        boolean resultsVisible = sessionRepository.findById(submission.getSessionId())
                .map(this::verdictsVisible)
                .orElse(false);

        return submissionMapper.toCandidateView(submission, results, resultsVisible);
    }

    /** While the exam runs, or once Module 9 has published the contest's results. */
    private boolean verdictsVisible(AssessmentSession session) {
        return session.getStatus() == SessionStatus.IN_PROGRESS
                || resultVisibility.isPublished(session.getContestId());
    }

    public PagedResponse<SubmissionSummaryResponse> getMyHistory(
            UUID candidateId,
            UUID questionId,
            SubmissionType type,
            Pageable pageable
    ) {
        Page<Submission> page = type == null
                ? submissionRepository.findByCandidateIdAndQuestionId(candidateId, questionId, pageable)
                : submissionRepository.findByCandidateIdAndQuestionIdAndSubmissionType(
                        candidateId, questionId, type, pageable);

        UUID countedSubmissionId = null;
        if (!page.isEmpty()) {
            // Reuse the session ID from the fetched submissions
            UUID sessionId = page.getContent().get(0).getSessionId();

            // Reuse your existing native query to find the counted submission
            countedSubmissionId = submissionRepository.findCountedSubmissions(sessionId).stream()
                    .filter(sub -> sub.getQuestionId().equals(questionId))
                    .map(Submission::getId)
                    .findFirst()
                    .orElse(null);
        }

        // Plan 2.10: once the session is over, verdicts stay hidden until results are published
        boolean resultsVisible = page.isEmpty() || sessionRepository
                .findById(page.getContent().get(0).getSessionId())
                .map(this::verdictsVisible)
                .orElse(false);

        final UUID finalCountedId = countedSubmissionId;
        List<SubmissionSummaryResponse> content = page.getContent().stream()
                .map(sub -> resultsVisible
                        ? SubmissionSummaryResponse.from(sub, sub.getId().equals(finalCountedId))
                        : SubmissionSummaryResponse.withoutResults(sub))
                .toList();

        return new PagedResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
    public void rejudge(UUID submissionId, UUID actorId) {
        Submission submission = persistenceService.prepareForRejudge(submissionId);

        auditService.log(
                actorId,
                "SUBMISSION_REJUDGED",
                "SUBMISSION",
                submission.getId(),
                "questionId=" + submission.getQuestionId()
                        + ", candidateId=" + submission.getCandidateId()
        );

        try {
            queueService.push(buildJobFromStored(submission));
        } catch (Exception e) {
            persistenceService.markSystemError(submission.getId())
                    .ifPresent(eventPublisher::publishEvent);
            throw new SubmissionQueueUnavailableException(
                    "Submission queue is unavailable"
            );
        }
    }

    /**
     * Rebuilds a queue job from a stored submission against the question's
     * current test cases. Used by rejudge and the recovery scheduler.
     */
    public QueuedSubmissionJob buildJobFromStored(Submission submission) {
        List<TestCase> testCases = testCaseRepository
                .findByQuestionIdOrderByOrderIndexAsc(submission.getQuestionId());

        Question question = questionRepository.findById(submission.getQuestionId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Question not found")
                );

        List<QueuedSubmissionJob.TestCasePayload> payloads = testCases.stream()
                .map(testCase -> new QueuedSubmissionJob.TestCasePayload(
                        testCase.getId(),
                        testCase.getInput(),
                        testCase.getExpectedOutput(),
                        question.getTimeLimitMs(),
                        question.getMemoryLimitKb()
                ))
                .toList();

        return new QueuedSubmissionJob(
                submission.getId(),
                submission.getQuestionId(),
                submission.getSessionId(),
                submission.getSourceCode(),
                submission.getLanguage(),
                payloads
        );
    }

    public PagedResponse<ContestSubmissionRowResponse> getContestSubmissions(
            UUID contestId,
            UUID candidateId,
            UUID questionId,
            SubmissionType type,
            SubmissionStatus status,
            Pageable pageable
    ) {
        Page<Submission> page = submissionRepository.findContestSubmissions(
                contestId,
                candidateId,
                questionId,
                type,
                status,
                pageable
        );

        List<UUID> candidateIds = page.getContent().stream()
                .map(Submission::getCandidateId)
                .distinct()
                .toList();

        Map<UUID, User> candidates = userRepository.findAllById(candidateIds)
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<ContestSubmissionRowResponse> content = page.getContent().stream()
                .map(sub -> ContestSubmissionRowResponse.from(
                        sub,
                        candidates.get(sub.getCandidateId())
                ))
                .toList();

        return new PagedResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
