package com.codepulse_backend.result.service;

import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.common.exception.ConflictException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.result.ResultAuditAction;
import com.codepulse_backend.result.dto.LeaderboardResponse;
import com.codepulse_backend.result.dto.MyResultResponse;
import com.codepulse_backend.result.dto.PublishResultsRequest;
import com.codepulse_backend.result.dto.ResultReadiness;
import com.codepulse_backend.result.dto.ResultResponse;
import com.codepulse_backend.result.dto.UnpublishResultsRequest;
import com.codepulse_backend.result.entity.ManualEvaluation;
import com.codepulse_backend.result.entity.Result;
import com.codepulse_backend.result.entity.ResultQuestionScore;
import com.codepulse_backend.result.event.ResultsPublishedEvent;
import com.codepulse_backend.result.repository.ManualEvaluationRepository;
import com.codepulse_backend.result.repository.ResultQuestionScoreRepository;
import com.codepulse_backend.result.repository.ResultRepository;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.session.AssessmentSessionRepository;
import com.codepulse_backend.session.SessionStatus;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Module 9 results: recompute, rank, publish and read.
 * <p>
 * Confirmed Module 8 semantics (plan Step 0): SessionScoringCompletedEvent is published
 * once a finalized session has no PENDING SUBMIT left, including a session that ended
 * with no submissions, and again after a rejudge of that session completes. It may be
 * published with or without an active transaction, so recomputeCandidate uses REQUIRES_NEW.
 * <p>
 * Every write takes the contest row lock first (plan 2.11): lock order is contest, then results.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResultService {

    private final ContestRepository contestRepository;
    private final ContestCandidateRepository contestCandidateRepository;
    private final QuestionRepository questionRepository;
    private final AssessmentSessionRepository sessionRepository;
    private final SubmissionRepository submissionRepository;
    private final ResultRepository resultRepository;
    private final ResultQuestionScoreRepository scoreRepository;
    private final ManualEvaluationRepository evaluationRepository;
    private final UserRepository userRepository;
    private final ResultCalculator calculator;
    private final RankingService rankingService;
    private final ResultReadinessService readinessService;
    private final ResultMapper mapper;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    // ─── Recompute ───────────────────────────────────────────────────────────

    /** Called from event listeners; its own transaction whatever the caller has. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recomputeCandidate(UUID contestId, UUID candidateId) {
        Contest contest = lockContest(contestId);
        if (contest.isResultsPublished()) {
            log.warn("Skipping result recompute for candidate {}: contest {} results are published",
                    candidateId, contestId);
            return;
        }
        recomputeCandidateLocked(contest, candidateId);
        rerank(contestId);
    }

    /** At contest completion: an ABSENT row for everyone assigned who never started. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createAbsentResults(UUID contestId) {
        Contest contest = lockContest(contestId);
        if (contest.isResultsPublished()) {
            return;
        }
        Set<UUID> withSession = sessionRepository.findAllByContestId(contestId).stream()
                .map(AssessmentSession::getCandidateId)
                .collect(Collectors.toSet());
        for (UUID candidateId : assignedCandidateIds(contestId)) {
            if (!withSession.contains(candidateId)) {
                recomputeCandidateLocked(contest, candidateId);
            }
        }
        rerank(contestId);
    }

    /** Admin "Recompute": the safety net for a lost event. */
    @Transactional
    public LeaderboardResponse recomputeContest(UUID contestId, UUID actorId) {
        Contest contest = lockContest(contestId);
        if (contest.isResultsPublished()) {
            throw new ConflictException("RESULTS_PUBLISHED_LOCKED",
                    "Results are published. Unpublish them before recomputing");
        }
        recomputeAllLocked(contest);
        auditService.log(actorId, ResultAuditAction.RESULTS_RECOMPUTED, "CONTEST", contestId,
                "Results recomputed manually");
        return buildLeaderboard(contest);
    }

    /** Caller holds the contest lock (see {@link #lockContest}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeAllLocked(Contest contest) {
        for (UUID candidateId : assignedCandidateIds(contest.getId())) {
            recomputeCandidateLocked(contest, candidateId);
        }
        rerank(contest.getId());
    }

    /**
     * Recomputes one candidate's row. Caller holds the contest lock and re-ranks afterwards.
     * Does nothing while the session is still running or judging; a later event comes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeCandidateLocked(Contest contest, UUID candidateId) {
        List<ResultCalculator.QuestionInput> questions = questionRepository
                .findAllByContestIdOrderByOrderIndex(contest.getId()).stream()
                .map(q -> new ResultCalculator.QuestionInput(q.getId(), q.getPoints()))
                .toList();

        AssessmentSession session = sessionRepository
                .findByContestIdAndCandidateId(contest.getId(), candidateId)
                .orElse(null);

        if (session == null) {
            if (contest.getStatus() == ContestStatus.COMPLETED) {
                save(contest.getId(), candidateId, null, calculator.absent(questions));
            }
            return;
        }

        if (session.getStatus() == SessionStatus.IN_PROGRESS
                || submissionRepository.countBySessionIdAndSubmissionTypeAndStatus(
                        session.getId(), SubmissionType.SUBMIT, SubmissionStatus.PENDING) > 0) {
            return;
        }

        Map<UUID, ResultCalculator.CountedInput> counted = submissionRepository
                .findCountedSubmissions(session.getId()).stream()
                .collect(Collectors.toMap(
                        Submission::getQuestionId,
                        s -> new ResultCalculator.CountedInput(s.getId(), s.getScore(), s.getSubmittedAt()),
                        (a, b) -> a
                ));

        long systemErrors = submissionRepository.countBySessionIdAndSubmissionTypeAndStatus(
                session.getId(), SubmissionType.SUBMIT, SubmissionStatus.SYSTEM_ERROR);

        ResultCalculator.CandidateResult computed = calculator.calculate(
                questions, counted, activeOverrides(session.getId()), systemErrors, session.getStartedAt());

        save(contest.getId(), candidateId, session.getId(), computed);
    }

    /** Caller holds the contest lock. Changed ranks are saved by dirty checking. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rerank(UUID contestId) {
        List<Result> results = resultRepository.findAllByContestId(contestId);
        Map<UUID, Integer> ranks = rankingService.rank(results.stream()
                .map(r -> new RankingService.RankInput(r.getId(), r.getStatus(), r.getTotalScore(), r.getTimeTakenSeconds()))
                .toList());
        for (Result result : results) {
            Integer rank = ranks.get(result.getId());
            if (!Objects.equals(rank, result.getRank())) {
                result.setRank(rank);
            }
        }
    }

    // ─── Publish ─────────────────────────────────────────────────────────────

    @Transactional
    public LeaderboardResponse publish(UUID contestId, PublishResultsRequest request, UUID actorId) {
        Contest contest = lockContest(contestId);

        if (contest.isResultsPublished()) {
            throw new ConflictException("RESULTS_ALREADY_PUBLISHED", "These results are already published");
        }
        if (contest.getStatus() != ContestStatus.COMPLETED) {
            throw new ConflictException("RESULTS_NOT_READY",
                    "The contest hasn't finished yet. Results can be published after it ends");
        }

        // Published numbers are fresh by construction
        recomputeAllLocked(contest);

        ResultReadiness readiness = readinessService.build(contest, resultRepository.findAllByContestId(contestId));
        if (readiness.inProgress() > 0 || readiness.judging() > 0 || readiness.missing() > 0) {
            throw new ConflictException("RESULTS_NOT_READY", notReadyMessage(readiness));
        }
        boolean acknowledged = request != null && request.acknowledgeFlagged();
        if (readiness.needsReview() > 0 && !acknowledged) {
            throw new ConflictException("RESULTS_NEED_REVIEW",
                    readiness.needsReview() + " result(s) need review. Acknowledge them to publish anyway");
        }

        contest.setResultsPublishedAt(Instant.now());
        contest.setResultsPublishedBy(actorId);

        auditService.log(actorId, ResultAuditAction.RESULTS_PUBLISHED, "CONTEST", contestId,
                "scored=" + readiness.scored() + ", needsReview=" + readiness.needsReview()
                        + ", absent=" + readiness.absent() + ", acknowledgeFlagged=" + acknowledged);

        // Module 11 consumes this; listeners should be AFTER_COMMIT
        eventPublisher.publishEvent(new ResultsPublishedEvent(contestId));

        return buildLeaderboard(contest);
    }

    @Transactional
    public LeaderboardResponse unpublish(UUID contestId, UnpublishResultsRequest request, UUID actorId) {
        Contest contest = lockContest(contestId);

        if (!contest.isResultsPublished()) {
            throw new ConflictException("RESULTS_NOT_PUBLISHED", "These results aren't published");
        }

        contest.setResultsPublishedAt(null);
        contest.setResultsPublishedBy(null);

        auditService.log(actorId, ResultAuditAction.RESULTS_UNPUBLISHED, "CONTEST", contestId,
                "reason=" + request.reason());

        return buildLeaderboard(contest);
    }

    // ─── Reads ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LeaderboardResponse getLeaderboard(UUID contestId) {
        return buildLeaderboard(findContest(contestId));
    }

    @Transactional(readOnly = true)
    public ResultResponse getCandidateResult(UUID contestId, UUID candidateId) {
        Contest contest = findContest(contestId);
        Result result = resultRepository.findByContestIdAndCandidateId(contestId, candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("RESULT_NOT_FOUND"));

        List<ResultQuestionScore> scores = scoreRepository.findAllByResultId(result.getId());
        List<Question> questions = questionRepository.findAllByContestIdOrderByOrderIndex(contestId);
        User candidate = userRepository.findById(candidateId).orElse(null);

        AssessmentSession session = null;
        Map<UUID, int[]> attemptStats = new HashMap<>();
        List<ManualEvaluation> evaluations = List.of();

        if (result.getSessionId() != null) {
            session = sessionRepository.findById(result.getSessionId()).orElse(null);
            for (Object[] row : submissionRepository.countBySessionGroupedByQuestionAndStatus(
                    result.getSessionId(), SubmissionType.SUBMIT)) {
                UUID questionId = (UUID) row[0];
                SubmissionStatus status = (SubmissionStatus) row[1];
                int count = ((Number) row[2]).intValue();
                int[] stats = attemptStats.computeIfAbsent(questionId, k -> new int[]{0, 0});
                stats[0] += count;
                if (status == SubmissionStatus.SYSTEM_ERROR) {
                    stats[1] += count;
                }
            }
            evaluations = evaluationRepository.findAllBySessionIdOrderByEvaluatedAtDescCreatedAtDesc(result.getSessionId());
        }

        Map<UUID, User> evaluators = usersById(evaluations.stream().map(ManualEvaluation::getEvaluatorId).toList());

        return mapper.toStaffView(
                contest,
                result,
                scores,
                questions,
                candidate,
                session,
                countedSubmissions(scores),
                attemptStats,
                evaluations,
                evaluators,
                resultRepository.countByContestIdAndRankIsNotNull(contestId)
        );
    }

    /**
     * Candidate's own result. Unassigned (or DRAFT) → 404 so contest IDs can't be probed.
     * Unpublished → published:false with no result data (plan 2.10).
     */
    @Transactional(readOnly = true)
    public MyResultResponse getMyResult(UUID contestId, UUID candidateId) {
        Contest contest = findContest(contestId);
        if (contest.getStatus() == ContestStatus.DRAFT
                || !contestCandidateRepository.existsByContestIdAndCandidateId(contestId, candidateId)) {
            throw new ResourceNotFoundException("Contest not found with id: " + contestId);
        }
        if (!contest.isResultsPublished()) {
            return MyResultResponse.unpublished(contest.getId(), contest.getTitle());
        }

        Result result = resultRepository.findByContestIdAndCandidateId(contestId, candidateId).orElse(null);
        List<ResultQuestionScore> scores = result == null ? List.of() : scoreRepository.findAllByResultId(result.getId());

        return mapper.toCandidateView(
                contest,
                result,
                scores,
                questionRepository.findAllByContestIdOrderByOrderIndex(contestId),
                countedSubmissions(scores),
                resultRepository.countByContestIdAndRankIsNotNull(contestId)
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** PESSIMISTIC_WRITE on the contest row; always the first lock a result write takes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Contest lockContest(UUID contestId) {
        return contestRepository.findByIdForUpdate(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("Contest not found with id: " + contestId));
    }

    private LeaderboardResponse buildLeaderboard(Contest contest) {
        UUID contestId = contest.getId();
        List<Result> results = resultRepository.findAllByContestId(contestId);
        List<Question> questions = questionRepository.findAllByContestIdOrderByOrderIndex(contestId);

        Map<UUID, List<ResultQuestionScore>> scoresByResult = results.isEmpty()
                ? Map.of()
                : scoreRepository.findAllByResultIdIn(results.stream().map(Result::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ResultQuestionScore::getResultId));

        List<UUID> userIds = new ArrayList<>(results.stream().map(Result::getCandidateId).toList());
        if (contest.getResultsPublishedBy() != null) {
            userIds.add(contest.getResultsPublishedBy());
        }

        return mapper.toLeaderboard(
                contest,
                questions,
                results,
                scoresByResult,
                usersById(userIds),
                readinessService.build(contest, results)
        );
    }

    private void save(UUID contestId, UUID candidateId, UUID sessionId, ResultCalculator.CandidateResult computed) {
        Result result = resultRepository.findByContestIdAndCandidateId(contestId, candidateId)
                .orElseGet(Result::new);

        result.setContestId(contestId);
        result.setCandidateId(candidateId);
        result.setSessionId(sessionId);
        result.setStatus(computed.status());
        result.setReviewReasons(new ArrayList<>(computed.reviewReasons().stream().map(Enum::name).toList()));
        result.setAutoScore(computed.autoScore());
        result.setTotalScore(computed.totalScore());
        result.setMaxScore(computed.maxScore());
        result.setAdjusted(computed.adjusted());
        result.setTimeTakenSeconds(computed.timeTakenSeconds());
        result.setComputedAt(Instant.now());
        result = resultRepository.saveAndFlush(result);

        scoreRepository.deleteAllByResultId(result.getId());

        UUID resultId = result.getId();
        scoreRepository.saveAll(computed.questions().stream().map(outcome -> {
            ResultQuestionScore score = new ResultQuestionScore();
            score.setResultId(resultId);
            score.setQuestionId(outcome.questionId());
            score.setCountedSubmissionId(outcome.countedSubmissionId());
            score.setManualEvaluationId(outcome.manualEvaluationId());
            score.setMaxPoints(outcome.maxPoints());
            score.setAutoScore(outcome.autoScore());
            score.setFinalScore(outcome.finalScore());
            score.setOverrideOutdated(outcome.overrideOutdated());
            return score;
        }).toList());
    }

    /** Newest evaluation per question; a revert (NULL score) means no active override. */
    private Map<UUID, ResultCalculator.OverrideInput> activeOverrides(UUID sessionId) {
        Map<UUID, ManualEvaluation> newest = new LinkedHashMap<>();
        for (ManualEvaluation e : evaluationRepository.findAllBySessionIdOrderByEvaluatedAtDescCreatedAtDesc(sessionId)) {
            newest.putIfAbsent(e.getQuestionId(), e);
        }
        Map<UUID, ResultCalculator.OverrideInput> active = new HashMap<>();
        newest.forEach((questionId, e) -> {
            if (e.getAdjustedScore() != null) {
                active.put(questionId, new ResultCalculator.OverrideInput(e.getId(), e.getSubmissionId(), e.getAdjustedScore()));
            }
        });
        return active;
    }

    private Map<UUID, Submission> countedSubmissions(List<ResultQuestionScore> scores) {
        List<UUID> ids = scores.stream()
                .map(ResultQuestionScore::getCountedSubmissionId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return submissionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Submission::getId, Function.identity()));
    }

    private Map<UUID, User> usersById(List<UUID> ids) {
        Set<UUID> unique = new HashSet<>(ids);
        unique.remove(null);
        if (unique.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(unique).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private List<UUID> assignedCandidateIds(UUID contestId) {
        return contestCandidateRepository.findAllByContestId(contestId).stream()
                .map(cc -> cc.getCandidate().getId())
                .toList();
    }

    private Contest findContest(UUID contestId) {
        return contestRepository.findById(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("Contest not found with id: " + contestId));
    }

    private static String notReadyMessage(ResultReadiness r) {
        List<String> parts = new ArrayList<>();
        if (r.inProgress() > 0) parts.add(r.inProgress() + " candidate(s) still taking the exam");
        if (r.judging() > 0) parts.add(r.judging() + " candidate(s) still being judged");
        if (r.missing() > 0) parts.add(r.missing() + " result(s) missing (press Recompute)");
        return "Results aren't ready: " + String.join(", ", parts);
    }
}
