package com.codepulse_backend.result.service;

import com.codepulse_backend.common.enums.ReviewReason;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.result.dto.LeaderboardEntry;
import com.codepulse_backend.result.dto.LeaderboardResponse;
import com.codepulse_backend.result.dto.ManualEvaluationResponse;
import com.codepulse_backend.result.dto.MyResultResponse;
import com.codepulse_backend.result.dto.ResultReadiness;
import com.codepulse_backend.result.dto.ResultResponse;
import com.codepulse_backend.result.entity.ManualEvaluation;
import com.codepulse_backend.result.entity.Result;
import com.codepulse_backend.result.entity.ResultQuestionScore;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.user.User;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only place result views are built. Staff and candidate views are separate
 * types with no shared base, so a staff-only field can't reach a candidate.
 */
@Component
public class ResultMapper {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public LeaderboardResponse toLeaderboard(
            Contest contest,
            List<Question> questions,
            List<Result> results,
            Map<UUID, List<ResultQuestionScore>> scoresByResult,
            Map<UUID, User> users,
            ResultReadiness readiness
    ) {
        List<LeaderboardResponse.QuestionColumn> columns = questions.stream()
                .map(q -> new LeaderboardResponse.QuestionColumn(q.getId(), q.getTitle(), q.getOrderIndex(), q.getPoints()))
                .toList();

        Comparator<Result> order = Comparator
                .comparing(Result::getRank, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> nameOf(users.get(r.getCandidateId())), String.CASE_INSENSITIVE_ORDER);

        List<LeaderboardEntry> entries = results.stream()
                .sorted(order)
                .map(result -> {
                    User user = users.get(result.getCandidateId());
                    Map<UUID, ResultQuestionScore> byQuestion = byQuestion(scoresByResult.get(result.getId()));
                    List<LeaderboardEntry.QuestionScoreCell> cells = questions.stream()
                            .map(q -> {
                                ResultQuestionScore s = byQuestion.get(q.getId());
                                return new LeaderboardEntry.QuestionScoreCell(
                                        q.getId(),
                                        s != null ? s.getFinalScore() : ZERO,
                                        q.getPoints(),
                                        s != null && s.getCountedSubmissionId() != null,
                                        s != null && s.getManualEvaluationId() != null
                                );
                            })
                            .toList();
                    return new LeaderboardEntry(
                            result.getId(),
                            result.getRank(),
                            result.getCandidateId(),
                            nameOf(user),
                            user != null ? user.getEmail() : null,
                            user != null ? user.getRollNumber() : null,
                            result.getStatus(),
                            reasons(result),
                            result.getTotalScore(),
                            result.getAutoScore(),
                            result.getMaxScore(),
                            result.isAdjusted(),
                            result.getTimeTakenSeconds(),
                            cells
                    );
                })
                .toList();

        BigDecimal maxScore = questions.stream()
                .map(q -> BigDecimal.valueOf(q.getPoints()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        User publishedBy = contest.getResultsPublishedBy() != null ? users.get(contest.getResultsPublishedBy()) : null;

        return new LeaderboardResponse(
                contest.getId(),
                contest.getTitle(),
                contest.getStatus(),
                contest.isResultsPublished(),
                contest.getResultsPublishedAt(),
                publishedBy != null ? publishedBy.getFullName() : null,
                maxScore,
                columns,
                readiness,
                entries
        );
    }

    /**
     * @param attemptStats questionId → {SUBMIT attempts, SYSTEM_ERROR attempts}
     * @param evaluations  this session's evaluations, newest first
     */
    public ResultResponse toStaffView(
            Contest contest,
            Result result,
            List<ResultQuestionScore> scores,
            List<Question> questions,
            User candidate,
            AssessmentSession session,
            Map<UUID, Submission> countedById,
            Map<UUID, int[]> attemptStats,
            List<ManualEvaluation> evaluations,
            Map<UUID, User> evaluators,
            long rankedCount
    ) {
        Map<UUID, ResultQuestionScore> byQuestion = byQuestion(scores);
        Map<UUID, List<ManualEvaluation>> historyByQuestion = evaluations.stream()
                .collect(Collectors.groupingBy(ManualEvaluation::getQuestionId));

        List<ResultResponse.QuestionResultView> views = questions.stream()
                .map(q -> {
                    ResultQuestionScore s = byQuestion.get(q.getId());
                    Submission counted = s != null && s.getCountedSubmissionId() != null
                            ? countedById.get(s.getCountedSubmissionId())
                            : null;
                    int[] stats = attemptStats.getOrDefault(q.getId(), new int[]{0, 0});
                    List<ManualEvaluationResponse> history = historyByQuestion.getOrDefault(q.getId(), List.of())
                            .stream()
                            .map(e -> toEvaluationResponse(e, evaluators.get(e.getEvaluatorId())))
                            .toList();
                    ManualEvaluationResponse active = s != null && s.getManualEvaluationId() != null
                            ? history.stream().filter(h -> h.id().equals(s.getManualEvaluationId())).findFirst().orElse(null)
                            : null;

                    return new ResultResponse.QuestionResultView(
                            q.getId(),
                            q.getTitle(),
                            q.getOrderIndex(),
                            q.getPoints(),
                            s != null ? s.getAutoScore() : ZERO,
                            s != null ? s.getFinalScore() : ZERO,
                            counted == null ? null : new ResultResponse.CountedSubmissionView(
                                    counted.getId(),
                                    counted.getLanguage(),
                                    counted.getStatus(),
                                    counted.getPassedCount(),
                                    counted.getTotalCount(),
                                    counted.getSubmittedAt()
                            ),
                            stats[0],
                            stats[1],
                            active,
                            s != null && s.isOverrideOutdated(),
                            history
                    );
                })
                .toList();

        return new ResultResponse(
                result.getId(),
                result.getContestId(),
                result.getCandidateId(),
                nameOf(candidate),
                candidate != null ? candidate.getEmail() : null,
                candidate != null ? candidate.getRollNumber() : null,
                result.getSessionId(),
                session != null ? session.getStatus() : null,
                session != null ? session.getStartedAt() : null,
                session != null ? session.getSubmittedAt() : null,
                result.getStatus(),
                reasons(result),
                result.getTotalScore(),
                result.getAutoScore(),
                result.getMaxScore(),
                result.getRank(),
                rankedCount,
                result.getTimeTakenSeconds(),
                contest.isResultsPublished(),
                result.getComputedAt(),
                views
        );
    }

    /** Checks the publish state before touching the result, so nothing leaks while unpublished. */
    public MyResultResponse toCandidateView(
            Contest contest,
            Result result,
            List<ResultQuestionScore> scores,
            List<Question> questions,
            Map<UUID, Submission> countedById,
            long rankedCount
    ) {
        if (!contest.isResultsPublished() || result == null) {
            return MyResultResponse.unpublished(contest.getId(), contest.getTitle());
        }

        Map<UUID, ResultQuestionScore> byQuestion = byQuestion(scores);
        List<MyResultResponse.MyQuestionResult> rows = questions.stream()
                .map(q -> {
                    ResultQuestionScore s = byQuestion.get(q.getId());
                    Submission counted = s != null && s.getCountedSubmissionId() != null
                            ? countedById.get(s.getCountedSubmissionId())
                            : null;
                    return new MyResultResponse.MyQuestionResult(
                            q.getId(),
                            q.getTitle(),
                            q.getOrderIndex(),
                            q.getPoints(),
                            s != null ? s.getFinalScore() : ZERO,
                            counted != null ? counted.getStatus() : null,
                            counted != null ? counted.getPassedCount() : null,
                            counted != null ? counted.getTotalCount() : null,
                            counted != null ? counted.getId() : null,
                            s != null && s.getManualEvaluationId() != null
                    );
                })
                .toList();

        return new MyResultResponse(
                contest.getId(),
                contest.getTitle(),
                true,
                contest.getResultsPublishedAt(),
                result.getStatus(),
                result.getTotalScore(),
                result.getMaxScore(),
                result.getRank(),
                rankedCount,
                result.isAdjusted(),
                rows
        );
    }

    public ManualEvaluationResponse toEvaluationResponse(ManualEvaluation e, User evaluator) {
        return new ManualEvaluationResponse(
                e.getId(),
                e.getSubmissionId(),
                e.getQuestionId(),
                e.getAdjustedScore(),
                e.getComments(),
                e.getEvaluatorId(),
                evaluator != null ? evaluator.getFullName() : null,
                e.getEvaluatedAt()
        );
    }

    private static Map<UUID, ResultQuestionScore> byQuestion(List<ResultQuestionScore> scores) {
        if (scores == null) {
            return Map.of();
        }
        return scores.stream().collect(Collectors.toMap(ResultQuestionScore::getQuestionId, Function.identity(), (a, b) -> a));
    }

    private static List<ReviewReason> reasons(Result result) {
        return result.getReviewReasons() == null
                ? List.of()
                : result.getReviewReasons().stream().map(ReviewReason::valueOf).toList();
    }

    private static String nameOf(User user) {
        return user != null ? user.getFullName() : "";
    }
}
