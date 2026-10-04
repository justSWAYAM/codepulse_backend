package com.codepulse_backend.analytics;

import com.codepulse_backend.auth.service.JwtService;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Backend plan Step 7: 200 candidates × 3 questions × 3 SUBMITs (1,800 submissions,
 * 7,200 test-case rows) on real Postgres. Results are built through Module 9's recompute,
 * then each analytics endpoint must answer in under 500 ms (warm).
 *
 * Measured on the dev laptop (2026-10-03, Postgres in Docker, best of 3 warm):
 * overview 29 ms, questions 26 ms, test cases 30 ms.
 */
@SpringBootTest
class AnalyticsPerformanceIntegrationTest {

    private static final int CANDIDATES = 200;
    private static final long BUDGET_MS = 500;

    @Autowired private WebApplicationContext context;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private User admin;
    private final UUID contestId = UUID.randomUUID();
    private final String tag = "a10p-" + System.nanoTime();

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestSecurityContextHolder.clearContext();
        admin = userRepository.save(User.builder()
                .email(tag + "-admin@codepulse.dev").passwordHash("x").fullName("Perf Admin").role(Role.ADMIN).isActive(true).build());
        UUID a = admin.getId();

        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, role, is_active)
                SELECT gen_random_uuid(), ? || '-' || i || '@codepulse.dev', 'x', 'Perf ' || i, 'CANDIDATE', true
                FROM generate_series(1, ?) i
                """, tag, CANDIDATES);

        jdbc.update("""
                INSERT INTO contests (id, title, start_time, end_time, duration_minutes, allowed_languages, status, created_by)
                VALUES (?, ?, now() - interval '3 hours', now() - interval '1 hour', 90, '["PYTHON"]', 'COMPLETED', ?)
                """, contestId, tag, a);

        jdbc.update("""
                INSERT INTO questions (id, contest_id, title, description, difficulty, points, order_index, created_by)
                SELECT gen_random_uuid(), ?, 'Q' || i, 'd', 'MEDIUM', 100, i, ? FROM generate_series(1, 3) i
                """, contestId, a);

        jdbc.update("""
                INSERT INTO test_cases (id, question_id, input, expected_output, is_sample, weight, order_index, created_by)
                SELECT gen_random_uuid(), q.id, 'in' || t, 'out' || t, t = 1, 25, t, ?
                FROM questions q, generate_series(1, 4) t WHERE q.contest_id = ?
                """, a, contestId);

        jdbc.update("""
                INSERT INTO contest_candidates (contest_id, candidate_id)
                SELECT ?, id FROM users WHERE email LIKE ? || '-%' AND role = 'CANDIDATE'
                """, contestId, tag);

        jdbc.update("""
                INSERT INTO assessment_sessions (id, contest_id, candidate_id, started_at, ends_at, status, submitted_at, created_by)
                SELECT gen_random_uuid(), ?, candidate_id, now() - interval '3 hours', now() - interval '90 minutes',
                       'SUBMITTED', now() - interval '100 minutes', ?
                FROM contest_candidates WHERE contest_id = ?
                """, contestId, a, contestId);

        // 3 SUBMITs per (session, question); statuses and scores vary deterministically
        jdbc.update("""
                INSERT INTO submissions (id, created_at, updated_at, session_id, question_id, candidate_id, language, source_code,
                                         submission_type, status, score, passed_count, total_count, submitted_at, evaluated_at)
                SELECT gen_random_uuid(), now(), now(), s.id, q.id, s.candidate_id,
                       CASE WHEN k % 2 = 0 THEN 'PYTHON' ELSE 'JAVA' END, 'print(1)', 'SUBMIT',
                       CASE (abs(hashtext(s.id::text || q.id::text || k)) % 4) WHEN 0 THEN 'ACCEPTED' WHEN 1 THEN 'WRONG_ANSWER'
                            WHEN 2 THEN 'TIME_LIMIT_EXCEEDED' ELSE 'RUNTIME_ERROR' END,
                       (abs(hashtext(s.id::text || q.id::text || k)) % 5) * 25, 2, 4,
                       s.started_at + (k * 10 + q.order_index) * interval '1 minute', now()
                FROM assessment_sessions s
                JOIN questions q ON q.contest_id = s.contest_id
                CROSS JOIN generate_series(1, 3) k
                WHERE s.contest_id = ?
                """, contestId);

        jdbc.update("""
                INSERT INTO submission_test_case_results (id, created_at, updated_at, submission_id, test_case_id, status, weight, is_sample)
                SELECT gen_random_uuid(), now(), now(), sub.id, tc.id,
                       CASE WHEN abs(hashtext(sub.id::text || tc.id::text)) % 3 = 0 THEN 'WRONG_ANSWER' ELSE 'PASSED' END,
                       tc.weight, tc.is_sample
                FROM submissions sub
                JOIN test_cases tc ON tc.question_id = sub.question_id
                JOIN assessment_sessions s ON s.id = sub.session_id
                WHERE s.contest_id = ?
                """, contestId);
    }

    @AfterEach
    void cleanUp() {
        TestSecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM result_question_scores WHERE result_id IN (SELECT id FROM results WHERE contest_id = ?)", contestId);
        jdbc.update("DELETE FROM results WHERE contest_id = ?", contestId);
        jdbc.update("DELETE FROM submission_test_case_results WHERE submission_id IN (SELECT s.id FROM submissions s JOIN assessment_sessions a ON a.id = s.session_id WHERE a.contest_id = ?)", contestId);
        jdbc.update("DELETE FROM submissions WHERE session_id IN (SELECT id FROM assessment_sessions WHERE contest_id = ?)", contestId);
        jdbc.update("DELETE FROM assessment_sessions WHERE contest_id = ?", contestId);
        jdbc.update("DELETE FROM contest_candidates WHERE contest_id = ?", contestId);
        jdbc.update("DELETE FROM test_cases WHERE question_id IN (SELECT id FROM questions WHERE contest_id = ?)", contestId);
        jdbc.update("DELETE FROM questions WHERE contest_id = ?", contestId);
        jdbc.update("DELETE FROM contests WHERE id = ?", contestId);
        jdbc.update("DELETE FROM audit_logs WHERE actor_id = ?", admin.getId());
        jdbc.update("DELETE FROM users WHERE email LIKE ? || '-%'", tag);
    }

    @Test
    void analyticsAnswerWithinBudgetOnSeveralHundredCandidates() throws Exception {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM submissions s JOIN assessment_sessions a ON a.id = s.session_id WHERE a.contest_id = ?",
                Long.class, contestId)).isEqualTo(CANDIDATES * 3L * 3L);

        String token = "Bearer " + jwtService.generateAccessToken(admin);
        mockMvc.perform(post("/api/contests/{id}/results/recompute", contestId).header("Authorization", token))
                .andExpect(status().isOk());

        UUID questionId = jdbc.queryForObject(
                "SELECT id FROM questions WHERE contest_id = ? ORDER BY order_index LIMIT 1", UUID.class, contestId);

        for (String path : List.of(
                "/api/contests/" + contestId + "/analytics/overview",
                "/api/contests/" + contestId + "/analytics/questions",
                "/api/contests/" + contestId + "/analytics/questions/" + questionId + "/test-cases")) {

            mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()); // warm-up

            long best = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk());
                best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
            }
            System.out.printf("Analytics timing %s: %d ms%n", path.substring(path.indexOf("/analytics")), best);
            assertThat(best).as(path).isLessThan(BUDGET_MS);
        }
    }
}
