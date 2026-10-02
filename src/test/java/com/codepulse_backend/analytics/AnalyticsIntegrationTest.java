package com.codepulse_backend.analytics;

import com.codepulse_backend.auth.security.CustomUserDetails;
import com.codepulse_backend.auth.service.JwtService;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.common.enums.TestCaseResultStatus;
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
import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import com.codepulse_backend.submission.repository.SubmissionTestCaseResultRepository;
import com.codepulse_backend.testcase.entity.TestCase;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Module 10 on real Postgres (the aggregates are PostgreSQL-only). Results are built
 * through Module 9's own recompute and override endpoints, so analytics are checked
 * against exactly what the leaderboard holds.
 *
 * Contest: 60 min, Q1 EASY 100 pts (T1 sample w10, T2 w40, T3 w50 — nobody passes T3),
 * Q2 HARD 50 pts. Five candidates take it, one is absent.
 *
 *   cand  Q1   Q2            total  last counted  session
 *   A     50   50 (AC @20m)   100    20m           SUBMITTED
 *   B     50   50 (AC @40m)   100    40m           SUBMITTED
 *   C     50   20 → 30 adj     80    25m           SUBMITTED
 *   D     50   —               50    50m           SUBMITTED
 *   E     50   —               50     5m           AUTO_SUBMITTED
 *   F     absent
 */
@SpringBootTest
class AnalyticsIntegrationTest {

    private static final String HIDDEN_INPUT = "hidden-input-a10";

    @Autowired private WebApplicationContext context;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private ContestCandidateRepository contestCandidateRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private TestCaseRepository testCaseRepository;
    @Autowired private AssessmentSessionRepository sessionRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private SubmissionTestCaseResultRepository resultRowRepository;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private final List<UUID> userIds = new ArrayList<>();
    private User admin, evaluator, candidateA;
    private Contest contest;
    private Question q1, q2;
    private TestCase t1, t2, t3, q2t1;
    private Submission cQ2;
    private Instant start;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String tag = Long.toString(System.nanoTime());

        admin = saveUser("a10-admin-" + tag + "@codepulse.dev", Role.ADMIN, "Admin " + tag);
        authenticate(admin);
        evaluator = saveUser("a10-eval-" + tag + "@codepulse.dev", Role.EVALUATOR, "Evaluator " + tag);

        start = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        contest = contestRepository.save(Contest.builder()
                .title("Analytics test " + tag)
                .description("integration test")
                .startTime(start)
                .endTime(start.plus(70, ChronoUnit.MINUTES))
                .durationMinutes(60)
                .allowedLanguages(List.of("PYTHON", "JAVA"))
                .status(ContestStatus.COMPLETED)
                .build());

        q1 = saveQuestion(1, 100, Difficulty.EASY);
        q2 = saveQuestion(2, 50, Difficulty.HARD);
        t1 = saveTestCase(q1, 1, true, 10, "2 3");
        t2 = saveTestCase(q1, 2, false, 40, HIDDEN_INPUT);
        t3 = saveTestCase(q1, 3, false, 50, "broken");
        q2t1 = saveTestCase(q2, 1, true, 100, "hello");

        String[] names = {"A", "B", "C", "D", "E", "F"};
        int[][] q1Minutes = {{10}, {30}, {15}, {50}, {5}};
        List<User> candidates = new ArrayList<>();
        for (String n : names) {
            User u = saveUser("a10-" + n.toLowerCase() + "-" + tag + "@codepulse.dev", Role.CANDIDATE, "Cand" + n + " " + tag);
            candidates.add(u);
            contestCandidateRepository.save(ContestCandidate.builder().contest(contest).candidate(u).build());
        }
        candidateA = candidates.get(0);

        for (int i = 0; i < 5; i++) {
            User u = candidates.get(i);
            AssessmentSession s = saveSession(u, i == 4 ? SessionStatus.AUTO_SUBMITTED : SessionStatus.SUBMITTED);
            // Q1: everyone passes T1 and T2 (50 points), nobody passes T3
            Submission q1Sub = saveSubmit(s, q1, u, "50.00", SubmissionStatus.WRONG_ANSWER, q1Minutes[i][0], "PYTHON");
            saveResultRow(q1Sub, t1, TestCaseResultStatus.PASSED);
            saveResultRow(q1Sub, t2, TestCaseResultStatus.PASSED);
            saveResultRow(q1Sub, t3, TestCaseResultStatus.WRONG_ANSWER);

            if (i == 0) {
                saveRun(s, q1, u);
                Submission a2 = saveSubmit(s, q2, u, "50.00", SubmissionStatus.ACCEPTED, 20, "JAVA");
                saveResultRow(a2, q2t1, TestCaseResultStatus.PASSED);
            } else if (i == 1) {
                Submission b2 = saveSubmit(s, q2, u, "50.00", SubmissionStatus.ACCEPTED, 40, "PYTHON");
                saveResultRow(b2, q2t1, TestCaseResultStatus.PASSED);
            } else if (i == 2) {
                cQ2 = saveSubmit(s, q2, u, "20.00", SubmissionStatus.WRONG_ANSWER, 25, "PYTHON");
                saveResultRow(cQ2, q2t1, TestCaseResultStatus.WRONG_ANSWER);
            }
        }
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
        jdbc.update("DELETE FROM test_cases WHERE question_id IN (SELECT id FROM questions WHERE contest_id = ?)", id);
        jdbc.update("DELETE FROM questions WHERE contest_id = ?", id);
        jdbc.update("DELETE FROM contests WHERE id = ?", id);
        for (UUID userId : userIds) jdbc.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
        for (UUID userId : userIds) jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    /** Builds results through Module 9 exactly as in production, plus one override (C's Q2: 20 → 30). */
    private void buildResults() throws Exception {
        mockMvc.perform(post("/api/contests/{id}/results/recompute", contest.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/submissions/{id}/evaluate", cQ2.getId())
                        .header("Authorization", bearer(evaluator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adjustedScore\":30,\"comments\":\"partial credit\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Overview: scores match the leaderboard (override included), buckets, sessions, attention")
    void overview() throws Exception {
        buildResults();

        overview(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.totalCandidates").value(6))
                .andExpect(jsonPath("$.data.coverage.withResult").value(6))
                .andExpect(jsonPath("$.data.coverage.provisional").value(false))
                .andExpect(jsonPath("$.data.scores.maxScore").value(150.0))
                .andExpect(jsonPath("$.data.scores.participants").value(5))
                .andExpect(jsonPath("$.data.scores.mean").value(76.0))
                .andExpect(jsonPath("$.data.scores.median").value(80.0))
                .andExpect(jsonPath("$.data.scores.min").value(50.0))
                .andExpect(jsonPath("$.data.scores.max").value(100.0))
                .andExpect(jsonPath("$.data.scores.stdDev").value(22.45))
                .andExpect(jsonPath("$.data.scores.medianTimeSeconds").value(1500))
                // 100/150 → bucket 7 (×2), 80/150 → 6, 50/150 → 4 (×2)
                .andExpect(jsonPath("$.data.scoreDistribution.length()").value(10))
                .andExpect(jsonPath("$.data.scoreDistribution[3].count").value(2))
                .andExpect(jsonPath("$.data.scoreDistribution[5].count").value(1))
                .andExpect(jsonPath("$.data.scoreDistribution[6].count").value(2))
                // time: 5m → 1, 20m → 4, 25m → 5, 40m → 7, 50m → 9 (of 60)
                .andExpect(jsonPath("$.data.timeDistribution[0].count").value(1))
                .andExpect(jsonPath("$.data.timeDistribution[3].count").value(1))
                .andExpect(jsonPath("$.data.timeDistribution[4].count").value(1))
                .andExpect(jsonPath("$.data.timeDistribution[6].count").value(1))
                .andExpect(jsonPath("$.data.timeDistribution[8].count").value(1))
                .andExpect(jsonPath("$.data.noTimeCount").value(0))
                .andExpect(jsonPath("$.data.sessions.submitted").value(4))
                .andExpect(jsonPath("$.data.sessions.autoSubmitted").value(1))
                .andExpect(jsonPath("$.data.sessions.notStarted").value(1))
                .andExpect(jsonPath("$.data.attention.adjusted").value(1))
                .andExpect(jsonPath("$.data.attention.absent").value(1))
                .andExpect(jsonPath("$.data.attention.autoSubmitted").value(1))
                .andExpect(jsonPath("$.data.attention.needsReview").value(0));
    }

    @Test
    @DisplayName("Questions: outcomes, difficulty check, activity, time to first Accepted")
    void questions() throws Exception {
        buildResults();

        mockMvc.perform(get("/api/contests/{id}/analytics/questions", contest.getId()).header("Authorization", bearer(evaluator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions.length()").value(2))
                // Q1: everyone 50/100 → played MEDIUM though labelled EASY
                .andExpect(jsonPath("$.data.questions[0].participants").value(5))
                .andExpect(jsonPath("$.data.questions[0].attempted").value(5))
                .andExpect(jsonPath("$.data.questions[0].partial").value(5))
                .andExpect(jsonPath("$.data.questions[0].fullMarks").value(0))
                .andExpect(jsonPath("$.data.questions[0].averageScore").value(50.0))
                .andExpect(jsonPath("$.data.questions[0].averageRatio").value(0.5))
                .andExpect(jsonPath("$.data.questions[0].observedDifficulty").value("MEDIUM"))
                .andExpect(jsonPath("$.data.questions[0].difficultyMatches").value(false))
                .andExpect(jsonPath("$.data.questions[0].submitCount").value(5))
                .andExpect(jsonPath("$.data.questions[0].runCount").value(1))
                .andExpect(jsonPath("$.data.questions[0].attemptsPerCandidate").value(1.0))
                .andExpect(jsonPath("$.data.questions[0].solvers").value(0))
                // Q2: A, B full; C partial (adjusted to 30); D, E not attempted
                .andExpect(jsonPath("$.data.questions[1].attempted").value(3))
                .andExpect(jsonPath("$.data.questions[1].fullMarks").value(2))
                .andExpect(jsonPath("$.data.questions[1].partial").value(1))
                .andExpect(jsonPath("$.data.questions[1].zero").value(2))
                .andExpect(jsonPath("$.data.questions[1].averageScore").value(26.0))
                .andExpect(jsonPath("$.data.questions[1].adjusted").value(1))
                .andExpect(jsonPath("$.data.questions[1].observedDifficulty").doesNotExist())   // 3 < 5: not enough data
                .andExpect(jsonPath("$.data.questions[1].difficultyMatches").value(false))
                .andExpect(jsonPath("$.data.questions[1].solvers").value(2))
                .andExpect(jsonPath("$.data.questions[1].medianSecondsToAccepted").value(1800))
                .andExpect(jsonPath("$.data.questions[1].verdicts[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.questions[1].verdicts[0].count").value(2))
                .andExpect(jsonPath("$.data.questions[1].languages.length()").value(2));
    }

    @Test
    @DisplayName("Test cases: counted submissions only, the case nobody passed is suspicious, no test data leaks")
    void testCases() throws Exception {
        buildResults();

        String json = mockMvc.perform(get("/api/contests/{id}/analytics/questions/{q}/test-cases", contest.getId(), q1.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidatesEvaluated").value(5))
                .andExpect(jsonPath("$.data.testCases.length()").value(3))
                .andExpect(jsonPath("$.data.testCases[0].passRate").value(1.0))
                .andExpect(jsonPath("$.data.testCases[0].suspicious").value(false))
                .andExpect(jsonPath("$.data.testCases[2].passed").value(0))
                .andExpect(jsonPath("$.data.testCases[2].wrongAnswer").value(5))
                .andExpect(jsonPath("$.data.testCases[2].suspicious").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(json).doesNotContain(HIDDEN_INPUT).doesNotContain("expectedOutput").doesNotContain("input\"");

        mockMvc.perform(get("/api/contests/{id}/analytics/questions/{q}/test-cases", contest.getId(), UUID.randomUUID())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Staff only, before and after publish; responses carry no identities")
    void authorization() throws Exception {
        buildResults();

        String json = overview(evaluator).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String questions = mockMvc.perform(get("/api/contests/{id}/analytics/questions", contest.getId())
                .header("Authorization", bearer(admin))).andReturn().getResponse().getContentAsString();
        assertThat(json + questions)
                .doesNotContain("@codepulse.dev")
                .doesNotContain(candidateA.getFullName())
                .doesNotContain(candidateA.getId().toString());

        overview(candidateA).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/contests/{id}/analytics/overview", contest.getId())).andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/contests/{id}/results/publish", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgeFlagged\":false}"))
                .andExpect(status().isOk());
        overview(candidateA).andExpect(status().isForbidden());
        overview(admin).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Live contests are provisional; DRAFT/PUBLISHED return ANALYTICS_NOT_AVAILABLE")
    void availability() throws Exception {
        contest.setStatus(ContestStatus.ONGOING);
        contestRepository.save(contest);
        jdbc.update("UPDATE assessment_sessions SET status = 'IN_PROGRESS' WHERE contest_id = ? AND candidate_id = ?",
                contest.getId(), candidateA.getId());

        overview(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.provisional").value(true))
                .andExpect(jsonPath("$.data.coverage.inProgress").value(1))
                .andExpect(jsonPath("$.data.coverage.withResult").value(0))
                .andExpect(jsonPath("$.data.scores.participants").value(0))
                .andExpect(jsonPath("$.data.scoreDistribution.length()").value(10));

        contest.setStatus(ContestStatus.PUBLISHED);
        contestRepository.save(contest);
        overview(admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ANALYTICS_NOT_AVAILABLE")));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ResultActions overview(User user) throws Exception {
        return mockMvc.perform(get("/api/contests/{id}/analytics/overview", contest.getId())
                .header("Authorization", bearer(user)));
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
                .email(email).passwordHash("not-used").fullName(name).role(role).isActive(true).build());
        userIds.add(user.getId());
        return user;
    }

    private Question saveQuestion(int order, int points, Difficulty difficulty) {
        return questionRepository.save(Question.builder()
                .contestId(contest.getId()).title("Question " + order).description("d")
                .difficulty(difficulty).points(points).timeLimitMs(2000).memoryLimitKb(262144).orderIndex(order)
                .build());
    }

    private TestCase saveTestCase(Question question, int order, boolean sample, int weight, String input) {
        TestCase tc = new TestCase();
        tc.setQuestionId(question.getId());
        tc.setInput(input);
        tc.setExpectedOutput("expected-" + order);
        tc.setSample(sample);
        tc.setWeight(weight);
        tc.setOrderIndex(order);
        return testCaseRepository.save(tc);
    }

    private AssessmentSession saveSession(User candidate, SessionStatus status) {
        AssessmentSession session = new AssessmentSession();
        session.setContestId(contest.getId());
        session.setCandidateId(candidate.getId());
        session.setStartedAt(start);
        session.setEndsAt(start.plus(60, ChronoUnit.MINUTES));
        session.setSubmittedAt(start.plus(55, ChronoUnit.MINUTES));
        session.setStatus(status);
        return sessionRepository.save(session);
    }

    private Submission saveSubmit(AssessmentSession session, Question question, User candidate, String score,
                                  SubmissionStatus status, int minutesAfterStart, String language) {
        Submission s = new Submission();
        s.setSessionId(session.getId());
        s.setQuestionId(question.getId());
        s.setCandidateId(candidate.getId());
        s.setLanguage(language);
        s.setSourceCode("print(1)");
        s.setSubmissionType(SubmissionType.SUBMIT);
        s.setStatus(status);
        s.setScore(new BigDecimal(score));
        s.setPassedCount(1);
        s.setTotalCount(1);
        s.setSubmittedAt(start.plus(minutesAfterStart, ChronoUnit.MINUTES));
        s.setEvaluatedAt(s.getSubmittedAt());
        return submissionRepository.save(s);
    }

    private void saveRun(AssessmentSession session, Question question, User candidate) {
        Submission s = new Submission();
        s.setSessionId(session.getId());
        s.setQuestionId(question.getId());
        s.setCandidateId(candidate.getId());
        s.setLanguage("PYTHON");
        s.setSourceCode("print(1)");
        s.setSubmissionType(SubmissionType.RUN);
        s.setStatus(SubmissionStatus.WRONG_ANSWER);
        s.setSubmittedAt(start.plus(3, ChronoUnit.MINUTES));
        s.setEvaluatedAt(s.getSubmittedAt());
        submissionRepository.save(s);
    }

    private void saveResultRow(Submission submission, TestCase testCase, TestCaseResultStatus status) {
        SubmissionTestCaseResult r = new SubmissionTestCaseResult();
        r.setSubmission(submission);
        r.setTestCaseId(testCase.getId());
        r.setStatus(status);
        r.setWeight(testCase.getWeight());
        r.setSample(testCase.isSample());
        resultRowRepository.save(r);
    }
}
