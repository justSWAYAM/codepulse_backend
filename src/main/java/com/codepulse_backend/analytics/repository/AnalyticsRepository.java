package com.codepulse_backend.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module 10 read-only aggregates (plan 2.2). Native PostgreSQL only — FILTER,
 * percentile_cont and width_bucket — so it is tested on real Postgres, never H2.
 * Every value is bound as a named parameter; nothing is concatenated into SQL.
 */
@Repository
@RequiredArgsConstructor
public class AnalyticsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    // ─── Overview ────────────────────────────────────────────────────────────

    public ScoreSummaryRow scoreSummary(UUID contestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status <> 'ABSENT')                     AS participants,
                       COUNT(*) FILTER (WHERE status = 'ABSENT')                      AS absent,
                       COUNT(*) FILTER (WHERE status = 'NEEDS_REVIEW')                AS needs_review,
                       COUNT(*) FILTER (WHERE adjusted)                               AS adjusted,
                       COUNT(*) FILTER (WHERE status <> 'ABSENT' AND total_score = 0) AS zero_scores,
                       AVG(total_score)        FILTER (WHERE status <> 'ABSENT')      AS mean,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY total_score)
                                               FILTER (WHERE status <> 'ABSENT')      AS median,
                       MIN(total_score)        FILTER (WHERE status <> 'ABSENT')      AS min_score,
                       MAX(total_score)        FILTER (WHERE status <> 'ABSENT')      AS max_score,
                       stddev_pop(total_score) FILTER (WHERE status <> 'ABSENT')      AS std_dev,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY time_taken_seconds)
                                               FILTER (WHERE status <> 'ABSENT')      AS median_time_seconds
                FROM results
                WHERE contest_id = :contestId
                """, contest(contestId), (rs, n) -> new ScoreSummaryRow(
                rs.getLong("participants"),
                rs.getLong("absent"),
                rs.getLong("needs_review"),
                rs.getLong("adjusted"),
                rs.getLong("zero_scores"),
                rs.getBigDecimal("mean"),
                nullableDouble(rs, "median"),
                rs.getBigDecimal("min_score"),
                rs.getBigDecimal("max_score"),
                rs.getBigDecimal("std_dev"),
                nullableDouble(rs, "median_time_seconds")
        ));
    }

    /** review_reasons is a JSON array stored as text by StringListConverter. */
    public Map<String, Long> reviewReasonCounts(UUID contestId) {
        return countsBy("""
                SELECT reason AS k, COUNT(*) AS n
                FROM results, jsonb_array_elements_text(COALESCE(NULLIF(review_reasons, ''), '[]')::jsonb) AS reason
                WHERE contest_id = :contestId AND status = 'NEEDS_REVIEW'
                GROUP BY reason
                """, contest(contestId), rs -> rs.getString("k"));
    }

    /** Bucket 1..10 of total_score / max_score; 100 % lands in bucket 10. */
    public Map<Integer, Long> scoreBuckets(UUID contestId) {
        return countsBy("""
                SELECT LEAST(width_bucket(total_score / max_score, 0, 1, 10), 10) AS k, COUNT(*) AS n
                FROM results
                WHERE contest_id = :contestId AND status <> 'ABSENT' AND max_score > 0
                GROUP BY k
                """, contest(contestId), rs -> rs.getInt("k"));
    }

    /** Bucket 1..10 of time taken as a share of the contest duration. */
    public Map<Integer, Long> timeBuckets(UUID contestId, int durationMinutes) {
        return countsBy("""
                SELECT LEAST(width_bucket(time_taken_seconds / (:durationSeconds * 1.0), 0, 1, 10), 10) AS k, COUNT(*) AS n
                FROM results
                WHERE contest_id = :contestId AND status <> 'ABSENT' AND time_taken_seconds IS NOT NULL
                GROUP BY k
                """, contest(contestId).addValue("durationSeconds", Math.max(1, durationMinutes) * 60L), rs -> rs.getInt("k"));
    }

    public SessionOutcomeRow sessionOutcomes(UUID contestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status = 'SUBMITTED')      AS submitted,
                       COUNT(*) FILTER (WHERE status = 'AUTO_SUBMITTED') AS auto_submitted,
                       COUNT(*) FILTER (WHERE status = 'IN_PROGRESS')    AS in_progress
                FROM assessment_sessions
                WHERE contest_id = :contestId
                """, contest(contestId), (rs, n) -> new SessionOutcomeRow(
                rs.getLong("submitted"), rs.getLong("auto_submitted"), rs.getLong("in_progress")));
    }

    // ─── Questions ───────────────────────────────────────────────────────────

    public List<QuestionScoreRow> questionScores(UUID contestId) {
        return jdbc.query("""
                SELECT q.id, q.title, q.order_index, q.points, q.difficulty,
                       COUNT(rqs.id)                                                                     AS participants,
                       COUNT(rqs.counted_submission_id)                                                  AS attempted,
                       COUNT(*) FILTER (WHERE rqs.max_points > 0 AND rqs.final_score >= rqs.max_points) AS full_marks,
                       COUNT(*) FILTER (WHERE rqs.final_score > 0 AND rqs.final_score < rqs.max_points) AS partial,
                       COUNT(*) FILTER (WHERE rqs.id IS NOT NULL AND rqs.final_score = 0)               AS zero,
                       AVG(rqs.final_score)                                                              AS avg_score,
                       COUNT(rqs.manual_evaluation_id)                                                   AS adjusted
                FROM questions q
                LEFT JOIN (result_question_scores rqs
                           JOIN results r ON r.id = rqs.result_id AND r.status <> 'ABSENT')
                       ON rqs.question_id = q.id
                WHERE q.contest_id = :contestId
                GROUP BY q.id
                ORDER BY q.order_index
                """, contest(contestId), (rs, n) -> new QuestionScoreRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getInt("order_index"),
                rs.getInt("points"),
                rs.getString("difficulty"),
                rs.getLong("participants"),
                rs.getLong("attempted"),
                rs.getLong("full_marks"),
                rs.getLong("partial"),
                rs.getLong("zero"),
                rs.getBigDecimal("avg_score"),
                rs.getLong("adjusted")
        ));
    }

    /** One row per (question, type, status, language); the service folds them per question. */
    public List<ActivityRow> questionActivity(UUID contestId) {
        return jdbc.query("""
                SELECT s.question_id, s.submission_type, s.status, s.language, COUNT(*) AS n
                FROM submissions s
                JOIN assessment_sessions a ON a.id = s.session_id
                WHERE a.contest_id = :contestId AND s.status <> 'PENDING'
                GROUP BY s.question_id, s.submission_type, s.status, s.language
                """, contest(contestId), (rs, n) -> new ActivityRow(
                rs.getObject("question_id", UUID.class),
                rs.getString("submission_type"),
                rs.getString("status"),
                rs.getString("language"),
                rs.getLong("n")
        ));
    }

    /** Median seconds from each solver's own start to their first Accepted SUBMIT. */
    public Map<UUID, AcceptedTimeRow> timeToFirstAccepted(UUID contestId) {
        Map<UUID, AcceptedTimeRow> byQuestion = new HashMap<>();
        jdbc.query("""
                SELECT question_id,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY seconds) AS median_seconds,
                       COUNT(*)                                             AS solvers
                FROM (SELECT s.question_id, EXTRACT(EPOCH FROM MIN(s.submitted_at) - a.started_at) AS seconds
                      FROM submissions s
                      JOIN assessment_sessions a ON a.id = s.session_id
                      WHERE a.contest_id = :contestId AND s.submission_type = 'SUBMIT' AND s.status = 'ACCEPTED'
                      GROUP BY s.question_id, s.session_id, a.started_at) firsts
                GROUP BY question_id
                """, contest(contestId), rs -> {
            byQuestion.put(rs.getObject("question_id", UUID.class),
                    new AcceptedTimeRow(nullableDouble(rs, "median_seconds"), rs.getLong("solvers")));
        });
        return byQuestion;
    }

    // ─── Test cases ──────────────────────────────────────────────────────────

    /** Counted submissions only: each candidate once, with the answer that scored (plan 2.7). */
    public List<TestCaseRow> testCaseStats(UUID contestId, UUID questionId) {
        return jdbc.query("""
                SELECT tc.id, tc.order_index, tc.is_sample, tc.weight,
                       COUNT(stcr.id)                                                AS evaluated,
                       COUNT(*) FILTER (WHERE stcr.status = 'PASSED')                AS passed,
                       COUNT(*) FILTER (WHERE stcr.status = 'WRONG_ANSWER')          AS wrong_answer,
                       COUNT(*) FILTER (WHERE stcr.status = 'TIME_LIMIT_EXCEEDED')   AS time_limit,
                       COUNT(*) FILTER (WHERE stcr.status = 'MEMORY_LIMIT_EXCEEDED') AS memory_limit,
                       COUNT(*) FILTER (WHERE stcr.status = 'RUNTIME_ERROR')         AS runtime_error,
                       COUNT(*) FILTER (WHERE stcr.status = 'COMPILATION_ERROR')     AS compilation_error
                FROM test_cases tc
                LEFT JOIN submission_test_case_results stcr
                       ON stcr.test_case_id = tc.id
                      AND stcr.submission_id IN (
                            SELECT rqs.counted_submission_id
                            FROM result_question_scores rqs
                            JOIN results r ON r.id = rqs.result_id
                            WHERE r.contest_id = :contestId AND rqs.question_id = :questionId
                              AND rqs.counted_submission_id IS NOT NULL)
                WHERE tc.question_id = :questionId
                GROUP BY tc.id
                ORDER BY tc.order_index
                """, contest(contestId).addValue("questionId", questionId), (rs, n) -> new TestCaseRow(
                rs.getObject("id", UUID.class),
                rs.getInt("order_index"),
                rs.getBoolean("is_sample"),
                rs.getInt("weight"),
                rs.getLong("evaluated"),
                rs.getLong("passed"),
                rs.getLong("wrong_answer"),
                rs.getLong("time_limit"),
                rs.getLong("memory_limit"),
                rs.getLong("runtime_error"),
                rs.getLong("compilation_error")
        ));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static MapSqlParameterSource contest(UUID contestId) {
        return new MapSqlParameterSource("contestId", contestId);
    }

    private <K> Map<K, Long> countsBy(String sql, MapSqlParameterSource params, KeyReader<K> key) {
        Map<K, Long> counts = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            counts.put(key.read(rs), rs.getLong("n"));
        });
        return counts;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    @FunctionalInterface
    private interface KeyReader<K> {
        K read(ResultSet rs) throws SQLException;
    }

    // ─── Rows ────────────────────────────────────────────────────────────────

    public record ScoreSummaryRow(long participants, long absent, long needsReview, long adjusted, long zeroScores,
                                  BigDecimal mean, Double median, BigDecimal min, BigDecimal max, BigDecimal stdDev,
                                  Double medianTimeSeconds) {}

    public record SessionOutcomeRow(long submitted, long autoSubmitted, long inProgress) {}

    public record QuestionScoreRow(UUID questionId, String title, int orderIndex, int points, String difficulty,
                                   long participants, long attempted, long fullMarks, long partial, long zero,
                                   BigDecimal averageScore, long adjusted) {}

    public record ActivityRow(UUID questionId, String submissionType, String status, String language, long count) {}

    public record AcceptedTimeRow(Double medianSeconds, long solvers) {}

    public record TestCaseRow(UUID testCaseId, int orderIndex, boolean sample, int weight, long evaluated, long passed,
                              long wrongAnswer, long timeLimit, long memoryLimit, long runtimeError,
                              long compilationError) {}
}
