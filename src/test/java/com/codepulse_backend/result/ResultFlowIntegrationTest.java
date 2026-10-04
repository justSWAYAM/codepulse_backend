package com.codepulse_backend.result;

import com.codepulse_backend.auth.security.CustomUserDetails;
import com.codepulse_backend.auth.service.JwtService;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.entity.ContestCandidate;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.session.AssessmentSessionRepository;
import com.codepulse_backend.session.SessionStatus;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.event.SessionScoringCompletedEvent;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 9 end to end through the HTTP API, on real Postgres (the counted-submission
 * query uses DISTINCT ON). Data is committed, because recompute runs in its own
 * transaction, and removed again after each test.
 *
 * Scenario: A solves both questions (150), B solves one (100), C never starts.
 */
@SpringBootTest
class ResultFlowIntegrationTest {

    private static final String COMMENT = "hard-coded the sample output 4f9a";

    @Autowired private WebApplicationContext context;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private ContestCandidateRepository contestCandidateRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private AssessmentSessionRepository sessionRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private final List<UUID> userIds = new ArrayList<>();

    private User admin, evaluator, a, b, c, outsider;
    private Contest contest;
    private Question q1, q2;
    private AssessmentSession sessionA, sessionB;
    private Submission aQ1, aQ2, bQ1, bQ1Lower;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String tag = Long.toString(System.nanoTime());

        admin = saveUser("m9-admin-" + tag + "@codepulse.dev", Role.ADMIN, "Admin " + tag);
        authenticate(admin);
        evaluator = saveUser("m9-eval-" + tag + "@codepulse.dev", Role.EVALUATOR, "Evaluator " + tag);
        a = saveUser("m9-a-" + tag + "@codepulse.dev", Role.CANDIDATE, "Asha " + tag);
        b = saveUser("m9-b-" + tag + "@codepulse.dev", Role.CANDIDATE, "Ravi " + tag);
        c = saveUser("m9-c-" + tag + "@codepulse.dev", Role.CANDIDATE, "Chitra " + tag);
        outsider = saveUser("m9-x-" + tag + "@codepulse.dev", Role.CANDIDATE, "Outsider " + tag);

        Instant now = Instant.now();
        contest = contestRepository.save(Contest.builder()
                .title("Module 9 flow " + tag)
                .description("integration test")
                .startTime(now.minus(2, ChronoUnit.HOURS))
                .endTime(now.minus(1, ChronoUnit.HOURS))
                .durationMinutes(60)
                .allowedLanguages(List.of("PYTHON"))
                .status(ContestStatus.COMPLETED)
                .build());
        for (User candidate : List.of(a, b, c)) {
            contestCandidateRepository.save(ContestCandidate.builder().contest(contest).candidate(candidate).build());
        }

        q1 = saveQuestion(1, 100);
        q2 = saveQuestion(2, 50);

        Instant started = now.minus(2, ChronoUnit.HOURS);
        sessionA = saveSession(a, started);
        sessionB = saveSession(b, started);

        aQ1 = saveSubmit(sessionA, q1, a, "100.00", SubmissionStatus.ACCEPTED, started.plus(40, ChronoUnit.MINUTES));
        aQ2 = saveSubmit(sessionA, q2, a, "50.00", SubmissionStatus.ACCEPTED, started.plus(50, ChronoUnit.MINUTES));
        bQ1 = saveSubmit(sessionB, q1, b, "100.00", SubmissionStatus.ACCEPTED, started.plus(30, ChronoUnit.MINUTES));
        bQ1Lower = saveSubmit(sessionB, q1, b, "20.00", SubmissionStatus.WRONG_ANSWER, started.plus(20, ChronoUnit.MINUTES));
        TestSecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TestSecurityContextHolder.clearContext();
        UUID id = contest.getId();
        jdbc.update("DELETE FROM result_question_scores WHERE result_id IN (SELECT id FROM results WHERE contest_id = ?)", id);
        jdbc.update("DELETE FROM results WHERE contest_id = ?", id);
        jdbc.update("DELETE FROM manual_evaluations WHERE session_id IN (SELECT id FROM assessment_sessions WHERE contest_id = ?)", id);
        jdbc.update("DELETE FROM submission_test_case_results WHERE submission_id IN (SELECT s.id FROM submissions s JOIN assessment_sessions a ON a.id = s.session_id WHERE a.contest_id = ?)", id);
        jdbc.update("DELETE FROM submissions WHERE session_id IN (SELECT id FROM assessment_sessions WHERE contest_id = ?)", id);
        jdbc.update("DELETE FROM assessment_sessions WHERE contest_id = ?", id);
        jdbc.update("DELETE FROM contest_candidates WHERE contest_id = ?", id);
        jdbc.update("DELETE FROM questions WHERE contest_id = ?", id);
        jdbc.update("DELETE FROM contests WHERE id = ?", id);
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
        }
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("Recompute ranks by score, absentees unranked; recompute is idempotent")
    void recompute_ranksAndIsIdempotent() throws Exception {
        recompute()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries.length()").value(3))
                .andExpect(jsonPath("$.data.entries[0].candidateId").value(a.getId().toString()))
                .andExpect(jsonPath("$.data.entries[0].rank").value(1))
                .andExpect(jsonPath("$.data.entries[0].totalScore").value(150.0))
                .andExpect(jsonPath("$.data.entries[1].candidateId").value(b.getId().toString()))
                .andExpect(jsonPath("$.data.entries[1].rank").value(2))
                .andExpect(jsonPath("$.data.entries[1].questionScores[1].attempted").value(false))
                .andExpect(jsonPath("$.data.entries[2].candidateId").value(c.getId().toString()))
                .andExpect(jsonPath("$.data.entries[2].status").value("ABSENT"))
                .andExpect(jsonPath("$.data.entries[2].rank").doesNotExist())
                .andExpect(jsonPath("$.data.readiness.readyToPublish").value(true));

        recompute().andExpect(status().isOk());

        assertThat(count("SELECT COUNT(*) FROM results WHERE contest_id = ?")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM result_question_scores WHERE result_id IN (SELECT id FROM results WHERE contest_id = ?)"))
                .isEqualTo(6);
    }

    @Test
    @DisplayName("SessionScoringCompletedEvent creates the candidate's result on its own")
    void scoringEvent_createsResult() throws Exception {
        eventPublisher.publishEvent(new SessionScoringCompletedEvent(sessionB.getId(), contest.getId(), b.getId()));

        mockMvc.perform(get("/api/contests/{id}/results/candidates/{cid}", contest.getId(), b.getId())
                        .header("Authorization", bearer(evaluator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalScore").value(100.0))
                .andExpect(jsonPath("$.data.rank").value(1))
                .andExpect(jsonPath("$.data.questions[0].countedSubmission.id").value(bQ1.getId().toString()))
                .andExpect(jsonPath("$.data.questions[0].submitAttempts").value(2));
    }

    @Test
    @DisplayName("Override changes total and rank; validation, counted-only and history")
    void override_reranksAndValidates() throws Exception {
        recompute().andExpect(status().isOk());

        evaluate(evaluator, aQ1, "160", COMMENT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ADJUSTED_SCORE_OUT_OF_RANGE")));
        evaluate(evaluator, aQ1, "10.555", COMMENT).andExpect(status().isBadRequest());
        evaluate(evaluator, aQ1, "20", " ").andExpect(status().isBadRequest());
        evaluate(evaluator, bQ1Lower, "20", COMMENT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SUBMISSION_NOT_COUNTED")));
        evaluate(a, aQ1, "20", COMMENT).andExpect(status().isForbidden());

        // A: 20 + 50 = 70 now trails B's 100
        evaluate(evaluator, aQ1, "20", COMMENT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalScore").value(70.0))
                .andExpect(jsonPath("$.data.autoScore").value(150.0))
                .andExpect(jsonPath("$.data.rank").value(2))
                .andExpect(jsonPath("$.data.questions[0].activeOverride.comments").value(COMMENT))
                .andExpect(jsonPath("$.data.questions[0].history.length()").value(1));

        leaderboard(admin)
                .andExpect(jsonPath("$.data.entries[0].candidateId").value(b.getId().toString()))
                .andExpect(jsonPath("$.data.entries[1].adjusted").value(true));

        // Revert: back to 150 and first place, history keeps both rows
        evaluate(evaluator, aQ1, null, "reviewed again, it was fine")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalScore").value(150.0))
                .andExpect(jsonPath("$.data.rank").value(1))
                .andExpect(jsonPath("$.data.questions[0].activeOverride").doesNotExist())
                .andExpect(jsonPath("$.data.questions[0].history.length()").value(2));
    }

    @Test
    @DisplayName("Candidates see nothing before publish and only their own numbers after")
    void publishBoundary_forCandidates() throws Exception {
        recompute().andExpect(status().isOk());
        evaluate(evaluator, aQ1, "20", COMMENT).andExpect(status().isOk());

        String before = myResult(a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(before).doesNotContain("totalScore").doesNotContain("rank").doesNotContain(q1.getId().toString());

        // Verdicts stay hidden after the exam until publish (Module 8)
        mockMvc.perform(get("/api/submissions/{id}", aQ1.getId()).header("Authorization", bearer(a)))
                .andExpect(jsonPath("$.status").doesNotExist());

        mockMvc.perform(post("/api/contests/{id}/results/publish", contest.getId())
                        .header("Authorization", bearer(evaluator)))
                .andExpect(status().isForbidden());
        publish(false).andExpect(status().isOk()).andExpect(jsonPath("$.data.published").value(true));

        String after = myResult(a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(true))
                .andExpect(jsonPath("$.data.totalScore").value(70.0))
                .andExpect(jsonPath("$.data.rank").value(2))
                .andExpect(jsonPath("$.data.rankedCount").value(2))
                .andExpect(jsonPath("$.data.adjusted").value(true))
                .andExpect(jsonPath("$.data.questions[0].adjusted").value(true))
                .andExpect(jsonPath("$.data.questions[0].verdict").value("ACCEPTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(after)
                .doesNotContain(COMMENT)
                .doesNotContain("autoScore")
                .doesNotContain(evaluator.getFullName())
                .doesNotContain(b.getId().toString())
                .doesNotContain(b.getFullName());

        myResult(c).andExpect(jsonPath("$.data.status").value("ABSENT"));
        myResult(outsider).andExpect(status().isNotFound());
        leaderboard(a).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/contests/{id}/results", contest.getId())).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/submissions/{id}", aQ1.getId()).header("Authorization", bearer(a)))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("Published results are frozen until unpublished")
    void published_locksEvaluationAndRejudge() throws Exception {
        recompute().andExpect(status().isOk());
        publish(false).andExpect(status().isOk());
        publish(false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("RESULTS_ALREADY_PUBLISHED")));

        evaluate(evaluator, aQ1, "20", COMMENT).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("RESULTS_PUBLISHED_LOCKED")));
        mockMvc.perform(post("/api/submissions/{id}/rejudge", aQ1.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        recompute().andExpect(status().isConflict());

        mockMvc.perform(post("/api/contests/{id}/results/unpublish", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/contests/{id}/results/unpublish", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"wrong test case on Q1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(false));

        myResult(a).andExpect(jsonPath("$.data.published").value(false));
        evaluate(evaluator, aQ1, "20", COMMENT).andExpect(status().isOk());

        assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action IN ('RESULTS_PUBLISHED','RESULTS_UNPUBLISHED')"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Judge errors flag the result; publishing then needs acknowledgement")
    void needsReview_requiresAcknowledgement() throws Exception {
        authenticate(admin);
        saveSubmit(sessionB, q2, b, "0.00", SubmissionStatus.SYSTEM_ERROR, Instant.now().minus(70, ChronoUnit.MINUTES));
        TestSecurityContextHolder.clearContext();

        recompute()
                .andExpect(jsonPath("$.data.readiness.needsReview").value(1))
                .andExpect(jsonPath("$.data.entries[1].status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.entries[1].reviewReasons[0]").value("UNRESOLVED_SYSTEM_ERROR"));

        publish(false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("RESULTS_NEED_REVIEW")));
        publish(true).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Publishing is refused while the contest or its sessions are still running")
    void notReady_isRefused() throws Exception {
        sessionB.setStatus(SessionStatus.IN_PROGRESS);
        sessionRepository.save(sessionB);

        recompute()
                .andExpect(jsonPath("$.data.readiness.inProgress").value(1))
                .andExpect(jsonPath("$.data.readiness.readyToPublish").value(false));
        publish(true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1 candidate(s) still taking the exam")));

        contest.setStatus(ContestStatus.ONGOING);
        contestRepository.save(contest);
        publish(true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("RESULTS_NOT_READY")));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ResultActions recompute() throws Exception {
        return mockMvc.perform(post("/api/contests/{id}/results/recompute", contest.getId())
                .header("Authorization", bearer(admin)));
    }

    private ResultActions publish(boolean acknowledge) throws Exception {
        return mockMvc.perform(post("/api/contests/{id}/results/publish", contest.getId())
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgeFlagged\":" + acknowledge + "}"));
    }

    private ResultActions leaderboard(User user) throws Exception {
        return mockMvc.perform(get("/api/contests/{id}/results", contest.getId()).header("Authorization", bearer(user)));
    }

    private ResultActions myResult(User user) throws Exception {
        return mockMvc.perform(get("/api/contests/{id}/results/me", contest.getId()).header("Authorization", bearer(user)));
    }

    private ResultActions evaluate(User user, Submission submission, String score, String comment) throws Exception {
        String body = "{\"adjustedScore\":" + (score == null ? "null" : score) + ",\"comments\":\"" + comment + "\"}";
        return mockMvc.perform(post("/api/submissions/{id}/evaluate", submission.getId())
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class, contest.getId());
        return n == null ? 0 : n;
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private void authenticate(User user) {
        CustomUserDetails details = new CustomUserDetails(user.getId(), user.getEmail(), user.getPasswordHash(),
                true, true, true, true, Collections.emptyList());
        // A fresh context: mutating the shared one would leak this login into MockMvc requests
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }

    private User saveUser(String email, Role role, String name) {
        User user = userRepository.save(User.builder()
                .email(email)
                .passwordHash("not-used")
                .fullName(name)
                .role(role)
                .isActive(true)
                .build());
        userIds.add(user.getId());
        return user;
    }

    private Question saveQuestion(int order, int points) {
        return questionRepository.save(Question.builder()
                .contestId(contest.getId())
                .title("Question " + order)
                .description("d")
                .difficulty(Difficulty.EASY)
                .points(points)
                .timeLimitMs(2000)
                .memoryLimitKb(262144)
                .orderIndex(order)
                .build());
    }

    private AssessmentSession saveSession(User candidate, Instant startedAt) {
        AssessmentSession session = new AssessmentSession();
        session.setContestId(contest.getId());
        session.setCandidateId(candidate.getId());
        session.setStartedAt(startedAt);
        session.setEndsAt(startedAt.plus(60, ChronoUnit.MINUTES));
        session.setSubmittedAt(startedAt.plus(55, ChronoUnit.MINUTES));
        session.setStatus(SessionStatus.SUBMITTED);
        return sessionRepository.save(session);
    }

    private Submission saveSubmit(AssessmentSession session, Question question, User candidate,
                                  String score, SubmissionStatus status, Instant at) {
        Submission s = new Submission();
        s.setSessionId(session.getId());
        s.setQuestionId(question.getId());
        s.setCandidateId(candidate.getId());
        s.setLanguage("PYTHON");
        s.setSourceCode("print(1)");
        s.setSubmissionType(SubmissionType.SUBMIT);
        s.setStatus(status);
        s.setScore(status == SubmissionStatus.SYSTEM_ERROR ? null : new BigDecimal(score));
        s.setPassedCount(1);
        s.setTotalCount(1);
        s.setSubmittedAt(at);
        s.setEvaluatedAt(at);
        return submissionRepository.save(s);
    }
}
