package com.codepulse_backend.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Plain SQL for hard-deleting a user. Most foreign keys to users(id) have no ON DELETE
 * action, so a candidate's assessment data is removed explicitly, children first.
 * Results are not touched here: ResultService removes them so leaderboards re-rank.
 */
@Repository
@RequiredArgsConstructor
public class UserDeletionRepository {

    private final JdbcTemplate jdbc;

    /** A running session or a queued submission would be judged against rows that no longer exist. */
    public boolean isInAssessment(UUID userId) {
        return exists("SELECT 1 FROM assessment_sessions WHERE candidate_id = ? AND status = 'IN_PROGRESS'", userId)
                || exists("SELECT 1 FROM submissions WHERE candidate_id = ? AND status = 'PENDING'", userId);
    }

    /** Content other users depend on: contests, questions, test cases, evaluations, published results. */
    public boolean hasAuthoredContent(UUID userId) {
        return exists("SELECT 1 FROM contests WHERE created_by = ? OR results_published_by = ?", userId, userId)
                || exists("SELECT 1 FROM questions WHERE created_by = ?", userId)
                || exists("SELECT 1 FROM test_cases WHERE created_by = ?", userId)
                || exists("SELECT 1 FROM manual_evaluations WHERE evaluator_id = ?", userId)
                || exists("SELECT 1 FROM assessment_sessions WHERE created_by = ? AND candidate_id <> ?", userId, userId);
    }

    /**
     * Children are deleted explicitly even where a migration declares ON DELETE CASCADE,
     * so this does not depend on how the schema was generated.
     */
    public void deleteUserAndAssessmentData(UUID userId) {
        String ownSessions = "SELECT id FROM assessment_sessions WHERE candidate_id = ?";
        String ownSubmissions = "SELECT id FROM submissions WHERE session_id IN (" + ownSessions + ")";
        jdbc.update("DELETE FROM manual_evaluations WHERE session_id IN (" + ownSessions + ")", userId);
        jdbc.update("DELETE FROM submission_test_case_results WHERE submission_id IN (" + ownSubmissions + ")", userId);
        jdbc.update("DELETE FROM submissions WHERE session_id IN (" + ownSessions + ")", userId);
        jdbc.update("DELETE FROM assessment_sessions WHERE candidate_id = ?", userId);
        jdbc.update("DELETE FROM contest_candidates WHERE candidate_id = ?", userId);
        jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
        jdbc.update("UPDATE questions SET updated_by = NULL WHERE updated_by = ?", userId);
        jdbc.update("UPDATE test_cases SET updated_by = NULL WHERE updated_by = ?", userId);
        jdbc.update("UPDATE assessment_sessions SET updated_by = NULL WHERE updated_by = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql + " LIMIT 1", args).isEmpty();
    }
}
