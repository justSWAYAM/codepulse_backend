package com.codepulse_backend.user;

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
import com.codepulse_backend.submission.repository.SubmissionRepository;
import com.codepulse_backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /api/users/{id} on real Postgres, so the Flyway foreign keys are the ones a
 * delete has to get past. Rows are committed and removed again after each test.
 *
 * Scenario: A scores 100 and B scores 50 in a completed contest the admin created.
 */
@SpringBootTest
class UserDeletionIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private ContestCandidateRepository contestCandidateRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private AssessmentSessionRepository sessionRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> contestIds = new ArrayList<>();
    private String tag;

    private User admin, a, b;
    private Contest contest;
    private Question question;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tag = Long.toString(System.nanoTime());

        admin = saveUser("del-admin-" + tag + "@codepulse.dev", Role.ADMIN);
        authenticate(admin);
        a = saveUser("del-a-" + tag + "@codepulse.dev", Role.CANDIDATE);
        b = saveUser("del-b-" + tag + "@codepulse.dev", Role.CANDIDATE);

        contest = saveContest(ContestStatus.COMPLETED);
        for (User candidate : List.of(a, b)) {
            contestCandidateRepository.save(ContestCandidate.builder().contest(contest).candidate(candidate).build());
        }
        question = questionRepository.save(Question.builder()
                .contestId(contest.getId())
                .title("Question 1")
                .description("d")
                .difficulty(Difficulty.EASY)
                .points(100)
                .timeLimitMs(2000)
                .memoryLimitKb(262144)
                .orderIndex(1)
                .build());

        Instant started = Instant.now().minus(2, ChronoUnit.HOURS);
        saveSubmit(saveSession(a, started, SessionStatus.SUBMITTED), a, "100.00", started.plus(30, ChronoUnit.MINUTES));
        saveSubmit(saveSession(b, started, SessionStatus.SUBMITTED), b, "50.00", started.plus(40, ChronoUnit.MINUTES));
        TestSecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TestSecurityContextHolder.clearContext();
        for (UUID id : contestIds) {
            jdbc.update("DELETE FROM result_question_scores WHERE result_id IN (SELECT id FROM results WHERE contest_id = ?)", id);
            jdbc.update("DELETE FROM results WHERE contest_id = ?", id);
            jdbc.update("DELETE FROM submission_test_case_results WHERE submission_id IN (SELECT s.id FROM submissions s JOIN assessment_sessions a ON a.id = s.session_id WHERE a.contest_id = ?)", id);
            jdbc.update("DELETE FROM submissions WHERE session_id IN (SELECT id FROM assessment_sessions WHERE contest_id = ?)", id);
            jdbc.update("DELETE FROM assessment_sessions WHERE contest_id = ?", id);
            jdbc.update("DELETE FROM contest_candidates WHERE contest_id = ?", id);
            jdbc.update("DELETE FROM questions WHERE contest_id = ?", id);
            jdbc.update("DELETE FROM contests WHERE id = ?", id);
        }
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM audit_logs WHERE actor_id = ? OR entity_id = ?", userId, userId);
        }
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("Deleting a candidate removes their assessment data and re-ranks the contest")
    void deleteCandidate_removesDataAndReranks() throws Exception {
        recompute().andExpect(status().isOk());
        assertThat(rankOf(b)).isEqualTo(2);

        deleteUser(a, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(userRepository.existsById(a.getId())).isFalse();
        assertThat(countFor("SELECT COUNT(*) FROM assessment_sessions WHERE candidate_id = ?", a)).isZero();
        assertThat(countFor("SELECT COUNT(*) FROM submissions WHERE candidate_id = ?", a)).isZero();
        assertThat(countFor("SELECT COUNT(*) FROM results WHERE candidate_id = ?", a)).isZero();
        assertThat(countFor("SELECT COUNT(*) FROM contest_candidates WHERE candidate_id = ?", a)).isZero();
        assertThat(rankOf(b)).isEqualTo(1);
        assertThat(countFor("SELECT COUNT(*) FROM audit_logs WHERE action = 'USER_DELETED' AND entity_id = ?", a)).isEqualTo(1);
        // B's data is untouched
        assertThat(countFor("SELECT COUNT(*) FROM submissions WHERE candidate_id = ?", b)).isEqualTo(1);
    }

    @Test
    @DisplayName("A user with no activity can be deleted")
    void deleteFreshUser_succeeds() throws Exception {
        User fresh = saveUser("del-fresh-" + tag + "@codepulse.dev", Role.EVALUATOR);

        deleteUser(fresh, admin).andExpect(status().isOk());

        assertThat(userRepository.existsById(fresh.getId())).isFalse();
    }

    @Test
    @DisplayName("A user who created contests must be deactivated instead")
    void deleteAuthor_isRejected() throws Exception {
        User author = saveUser("del-author-" + tag + "@codepulse.dev", Role.ADMIN);
        authenticate(author);
        saveContest(ContestStatus.DRAFT);
        TestSecurityContextHolder.clearContext();

        deleteUser(author, admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("USER_HAS_AUTHORED_CONTENT")));

        assertThat(userRepository.existsById(author.getId())).isTrue();
    }

    @Test
    @DisplayName("A candidate mid-assessment cannot be deleted")
    void deleteCandidateInProgress_isRejected() throws Exception {
        authenticate(admin);
        Contest ongoing = saveContest(ContestStatus.ONGOING);
        User busy = saveUser("del-busy-" + tag + "@codepulse.dev", Role.CANDIDATE);
        AssessmentSession session = new AssessmentSession();
        session.setContestId(ongoing.getId());
        session.setCandidateId(busy.getId());
        session.setStartedAt(Instant.now());
        session.setEndsAt(Instant.now().plus(1, ChronoUnit.HOURS));
        session.setStatus(SessionStatus.IN_PROGRESS);
        sessionRepository.save(session);
        TestSecurityContextHolder.clearContext();

        deleteUser(busy, admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("USER_IN_ASSESSMENT")));
    }

    @Test
    @DisplayName("An admin cannot delete their own account")
    void deleteSelf_isRejected() throws Exception {
        deleteUser(admin, admin).andExpect(status().is(422));
        assertThat(userRepository.existsById(admin.getId())).isTrue();
    }

    @Test
    @DisplayName("Only admins can delete users; unknown ids are 404")
    void delete_authorizationAndNotFound() throws Exception {
        deleteUser(b, a).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/users/{id}", UUID.randomUUID()).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
        assertThat(userRepository.existsById(b.getId())).isTrue();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private ResultActions deleteUser(User target, User actor) throws Exception {
        return mockMvc.perform(delete("/api/users/{id}", target.getId()).header("Authorization", bearer(actor)));
    }

    private ResultActions recompute() throws Exception {
        return mockMvc.perform(post("/api/contests/{id}/results/recompute", contest.getId())
                .header("Authorization", bearer(admin)));
    }

    private Integer rankOf(User candidate) {
        return jdbc.queryForObject("SELECT rank FROM results WHERE contest_id = ? AND candidate_id = ?",
                Integer.class, contest.getId(), candidate.getId());
    }

    private long countFor(String sql, User user) {
        Long n = jdbc.queryForObject(sql, Long.class, user.getId());
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

    private User saveUser(String email, Role role) {
        User user = userRepository.save(User.builder()
                .email(email)
                .passwordHash("not-used")
                .fullName("Delete test " + tag)
                .role(role)
                .isActive(true)
                .build());
        userIds.add(user.getId());
        return user;
    }

    private Contest saveContest(ContestStatus status) {
        Instant now = Instant.now();
        Contest saved = contestRepository.save(Contest.builder()
                .title("User deletion " + tag)
                .description("integration test")
                .startTime(now.minus(3, ChronoUnit.HOURS))
                .endTime(status == ContestStatus.COMPLETED ? now.minus(1, ChronoUnit.HOURS) : now.plus(1, ChronoUnit.HOURS))
                .durationMinutes(60)
                .allowedLanguages(List.of("PYTHON"))
                .status(status)
                .build());
        contestIds.add(saved.getId());
        return saved;
    }

    private AssessmentSession saveSession(User candidate, Instant startedAt, SessionStatus status) {
        AssessmentSession session = new AssessmentSession();
        session.setContestId(contest.getId());
        session.setCandidateId(candidate.getId());
        session.setStartedAt(startedAt);
        session.setEndsAt(startedAt.plus(60, ChronoUnit.MINUTES));
        session.setSubmittedAt(startedAt.plus(55, ChronoUnit.MINUTES));
        session.setStatus(status);
        return sessionRepository.save(session);
    }

    private void saveSubmit(AssessmentSession session, User candidate, String score, Instant at) {
        Submission s = new Submission();
        s.setSessionId(session.getId());
        s.setQuestionId(question.getId());
        s.setCandidateId(candidate.getId());
        s.setLanguage("PYTHON");
        s.setSourceCode("print(1)");
        s.setSubmissionType(SubmissionType.SUBMIT);
        s.setStatus(SubmissionStatus.ACCEPTED);
        s.setScore(new BigDecimal(score));
        s.setPassedCount(1);
        s.setTotalCount(1);
        s.setSubmittedAt(at);
        s.setEvaluatedAt(at);
        submissionRepository.save(s);
    }
}
