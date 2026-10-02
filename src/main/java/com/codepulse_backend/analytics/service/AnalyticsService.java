package com.codepulse_backend.analytics.service;

import com.codepulse_backend.analytics.dto.ContestAnalyticsResponse;
import com.codepulse_backend.analytics.dto.ContestAnalyticsResponse.Coverage;
import com.codepulse_backend.analytics.dto.QuestionAnalyticsResponse;
import com.codepulse_backend.analytics.dto.TestCaseAnalyticsResponse;
import com.codepulse_backend.analytics.repository.AnalyticsRepository;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.ReviewReason;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.exception.ConflictException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.result.dto.ResultReadiness;
import com.codepulse_backend.result.repository.ResultRepository;
import com.codepulse_backend.result.service.ResultReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module 10: read-only contest analytics for staff. Scores come from Module 9's results
 * (overrides included, so analytics never disagree with the leaderboard); activity comes
 * from Module 8's submissions (plan 2.1).
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final ContestRepository contestRepository;
    private final QuestionRepository questionRepository;
    private final ResultRepository resultRepository;
    private final ResultReadinessService readinessService;
    private final AnalyticsRepository analyticsRepository;
    private final AnalyticsCalculator calculator;

    @Transactional(readOnly = true)
    public ContestAnalyticsResponse getOverview(UUID contestId) {
        Contest contest = availableContest(contestId);
        Coverage coverage = coverage(contest);

        AnalyticsRepository.ScoreSummaryRow summary = analyticsRepository.scoreSummary(contestId);
        Map<Integer, Long> timeBuckets = analyticsRepository.timeBuckets(contestId, contest.getDurationMinutes());
        long timed = timeBuckets.values().stream().mapToLong(Long::longValue).sum();

        BigDecimal maxScore = BigDecimal.valueOf(questionRepository.findAllByContestIdOrderByOrderIndex(contestId)
                .stream().mapToInt(q -> q.getPoints()).sum());

        AnalyticsRepository.SessionOutcomeRow sessions = analyticsRepository.sessionOutcomes(contestId);
        long started = sessions.submitted() + sessions.autoSubmitted() + sessions.inProgress();

        Map<ReviewReason, Long> byReason = new EnumMap<>(ReviewReason.class);
        analyticsRepository.reviewReasonCounts(contestId).forEach((reason, count) -> {
            try {
                byReason.put(ReviewReason.valueOf(reason), count);
            } catch (IllegalArgumentException ignored) {
                // a reason this build doesn't know (written by a newer version); leave it out
            }
        });

        return new ContestAnalyticsResponse(
                contest.getId(),
                contest.getTitle(),
                contest.getStatus(),
                contest.getDurationMinutes(),
                coverage,
                new ContestAnalyticsResponse.ScoreSummary(
                        calculator.round2(maxScore),
                        summary.participants(),
                        calculator.round2(summary.mean()),
                        calculator.round2(summary.median()),
                        calculator.round2(summary.min()),
                        calculator.round2(summary.max()),
                        calculator.round2(summary.stdDev()),
                        summary.medianTimeSeconds() == null ? null : Math.round(summary.medianTimeSeconds())
                ),
                calculator.fillBuckets(analyticsRepository.scoreBuckets(contestId)),
                calculator.fillBuckets(timeBuckets),
                Math.max(0, summary.participants() - timed),
                new ContestAnalyticsResponse.SessionOutcomes(
                        sessions.submitted(),
                        sessions.autoSubmitted(),
                        sessions.inProgress(),
                        Math.max(0, coverage.totalCandidates() - started)
                ),
                new ContestAnalyticsResponse.AttentionSummary(
                        summary.needsReview(),
                        byReason,
                        summary.adjusted(),
                        sessions.autoSubmitted(),
                        summary.absent(),
                        summary.zeroScores()
                )
        );
    }

    @Transactional(readOnly = true)
    public QuestionAnalyticsResponse getQuestions(UUID contestId) {
        Contest contest = availableContest(contestId);

        Map<UUID, Activity> activity = new HashMap<>();
        for (AnalyticsRepository.ActivityRow row : analyticsRepository.questionActivity(contestId)) {
            activity.computeIfAbsent(row.questionId(), id -> new Activity()).add(row);
        }
        Map<UUID, AnalyticsRepository.AcceptedTimeRow> accepted = analyticsRepository.timeToFirstAccepted(contestId);

        List<QuestionAnalyticsResponse.QuestionStats> questions = analyticsRepository.questionScores(contestId).stream()
                .map(q -> {
                    Activity a = activity.getOrDefault(q.questionId(), new Activity());
                    AnalyticsRepository.AcceptedTimeRow time = accepted.get(q.questionId());
                    Difficulty label = Difficulty.valueOf(q.difficulty());
                    Difficulty observed = calculator.observedDifficulty(q.averageScore(), q.points(), q.attempted());
                    return new QuestionAnalyticsResponse.QuestionStats(
                            q.questionId(),
                            q.title(),
                            q.orderIndex(),
                            q.points(),
                            label,
                            q.participants(),
                            q.attempted(),
                            q.fullMarks(),
                            q.partial(),
                            q.zero(),
                            calculator.round2(q.averageScore()),
                            calculator.ratio(q.averageScore(), BigDecimal.valueOf(q.points())),
                            observed,
                            observed != null && observed == label,
                            q.adjusted(),
                            a.submits,
                            a.runs,
                            q.attempted() == 0 ? null : calculator.ratio(a.submits, q.attempted()).setScale(2, java.math.RoundingMode.HALF_UP),
                            time == null || time.medianSeconds() == null ? null : Math.round(time.medianSeconds()),
                            time == null ? 0 : time.solvers(),
                            a.verdicts(),
                            a.languages()
                    );
                })
                .toList();

        return new QuestionAnalyticsResponse(coverage(contest), questions);
    }

    @Transactional(readOnly = true)
    public TestCaseAnalyticsResponse getTestCases(UUID contestId, UUID questionId) {
        availableContest(contestId);
        var question = questionRepository.findById(questionId)
                .filter(q -> q.getContestId().equals(contestId))
                .orElseThrow(() -> new ResourceNotFoundException("Question not found in this contest"));

        List<AnalyticsRepository.TestCaseRow> rows = analyticsRepository.testCaseStats(contestId, questionId);
        List<BigDecimal> passRates = rows.stream().map(r -> calculator.ratio(r.passed(), r.evaluated())).toList();

        List<TestCaseAnalyticsResponse.TestCaseStats> stats = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            AnalyticsRepository.TestCaseRow r = rows.get(i);
            List<BigDecimal> peers = new ArrayList<>(passRates);
            peers.remove(i);
            stats.add(new TestCaseAnalyticsResponse.TestCaseStats(
                    r.testCaseId(),
                    r.orderIndex(),
                    r.sample(),
                    r.weight(),
                    r.evaluated(),
                    r.passed(),
                    passRates.get(i),
                    r.wrongAnswer(),
                    r.timeLimit(),
                    r.memoryLimit(),
                    r.runtimeError(),
                    r.compilationError(),
                    calculator.suspicious(r.evaluated(), r.passed(), peers)
            ));
        }

        long evaluated = rows.stream().mapToLong(AnalyticsRepository.TestCaseRow::evaluated).max().orElse(0);
        return new TestCaseAnalyticsResponse(question.getId(), question.getTitle(), evaluated, stats);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** DRAFT/PUBLISHED: nothing has happened yet (plan 2.6). */
    private Contest availableContest(UUID contestId) {
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("Contest not found with id: " + contestId));
        if (contest.getStatus() == ContestStatus.DRAFT || contest.getStatus() == ContestStatus.PUBLISHED) {
            throw new ConflictException("ANALYTICS_NOT_AVAILABLE", "Analytics are available once the contest starts");
        }
        return contest;
    }

    /** Module 9 readiness, reused rather than re-derived (plan 2.3). */
    private Coverage coverage(Contest contest) {
        ResultReadiness r = readinessService.build(contest, resultRepository.findAllByContestId(contest.getId()));
        return new Coverage(
                r.totalCandidates(),
                r.scored() + r.needsReview() + r.absent(),
                r.inProgress(),
                r.judging(),
                r.absent(),
                r.contestCompleted(),
                !r.contestCompleted() || r.inProgress() > 0 || r.judging() > 0
        );
    }

    /** Folds the per-(type, status, language) activity rows of one question. */
    private static final class Activity {
        long submits;
        long runs;
        final Map<SubmissionStatus, Long> verdicts = new EnumMap<>(SubmissionStatus.class);
        final Map<String, Long> languages = new HashMap<>();

        void add(AnalyticsRepository.ActivityRow row) {
            if ("RUN".equals(row.submissionType())) {
                runs += row.count();
                return;
            }
            submits += row.count();
            verdicts.merge(SubmissionStatus.valueOf(row.status()), row.count(), Long::sum);
            languages.merge(row.language(), row.count(), Long::sum);
        }

        List<QuestionAnalyticsResponse.VerdictCount> verdicts() {
            return verdicts.entrySet().stream()
                    .map(e -> new QuestionAnalyticsResponse.VerdictCount(e.getKey(), e.getValue()))
                    .sorted(Comparator
                            .comparing((QuestionAnalyticsResponse.VerdictCount v) -> v.status() != SubmissionStatus.ACCEPTED)
                            .thenComparing(QuestionAnalyticsResponse.VerdictCount::count, Comparator.reverseOrder()))
                    .toList();
        }

        List<QuestionAnalyticsResponse.LanguageCount> languages() {
            return languages.entrySet().stream()
                    .map(e -> new QuestionAnalyticsResponse.LanguageCount(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparing(QuestionAnalyticsResponse.LanguageCount::count, Comparator.reverseOrder()))
                    .toList();
        }
    }
}
