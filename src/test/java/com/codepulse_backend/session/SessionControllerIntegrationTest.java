package com.codepulse_backend.session;

import com.codepulse_backend.auth.service.JwtService;
import com.codepulse_backend.common.audit.AuditLogRepository;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.entity.ContestCandidate;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 6 Definition of Done, exercised end-to-end through the HTTP layer.
 * Not @Transactional on purpose: the expiry case depends on real commit/rollback behaviour.
 */
@SpringBootTest
@ActiveProfiles("test")
class SessionControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private ContestCandidateRepository contestCandidateRepository;
    @Autowired private AssessmentSessionRepository sessionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User candidate;
    private User otherCandidate;
    private User admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        candidate = saveUser("cand@codepulse.dev", Role.CANDIDATE);
        otherCandidate = saveUser("other@codepulse.dev", Role.CANDIDATE);
        admin = saveUser("admin-s@codepulse.dev", Role.ADMIN);
    }

    @AfterEach
    void cleanUp() {
        sessionRepository.deleteAll();
        contestCandidateRepository.deleteAll();
        contestRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Start computes ends_at on the server, capped at the contest end time")
    void start_computesEndsAtServerSide() throws Exception {
        // 60-minute duration but only ~30 minutes of contest window left -> capped at contest end
        Instant contestEnd = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        Contest contest = saveContest(ContestStatus.ONGOING, 60, contestEnd, candidate);

        mockMvc.perform(post("/api/contests/{id}/session/start", contest.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.resumed").value(false))
                .andExpect(jsonPath("$.data.serverTime").exists());

        AssessmentSession session = sessionRepository.findByContestIdAndCandidateId(contest.getId(), candidate.getId()).orElseThrow();
        assertThat(session.getEndsAt()).isEqualTo(contestEnd);
    }

    @Test
    @DisplayName("Calling start again (browser refresh) resumes the same session")
    void start_twice_resumesSameSession() throws Exception {
        Contest contest = saveContest(ContestStatus.ONGOING, 60, Instant.now().plus(2, ChronoUnit.HOURS), candidate);

        mockMvc.perform(post("/api/contests/{id}/session/start", contest.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().isOk());
        AssessmentSession first = sessionRepository.findByContestIdAndCandidateId(contest.getId(), candidate.getId()).orElseThrow();

        mockMvc.perform(post("/api/contests/{id}/session/start", contest.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumed").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(first.getId().toString()))
                .andExpect(jsonPath("$.data.endsAt").exists());

        assertThat(sessionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Submit ends the session; starting again is rejected with 422")
    void submit_thenStart_isRejected() throws Exception {
        Contest contest = saveContest(ContestStatus.ONGOING, 60, Instant.now().plus(2, ChronoUnit.HOURS), candidate);

        mockMvc.perform(post("/api/contests/{id}/session/start", contest.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/contests/{id}/session/submit", contest.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.submittedAt").exists());
        mockMvc.perform(post("/api/contests/{id}/session/start", contest.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().is(422));
    }

    @Test
    @DisplayName("Resuming an expired session auto-submits it, and the auto-submit is persisted")
    void start_afterExpiry_autoSubmitsAndPersists() throws Exception {
        Contest contest = saveContest(ContestStatus.ONGOING, 60, Instant.now().plus(2, ChronoUnit.HOURS), candidate);
        mockMvc.perform(post("/api/contests/{id}/session/start", contest.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().isOk());

        // Simulate the time limit passing before the scheduler has run
        AssessmentSession session = sessionRepository.findByContestIdAndCandidateId(contest.getId(), candidate.getId()).orElseThrow();
        session.setStartedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        session.setEndsAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        sessionRepository.save(session);

        mockMvc.perform(post("/api/contests/{id}/session/start", contest.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().is(422));

        // Must survive the exception thrown by start (previously rolled back)
        AssessmentSession after = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SessionStatus.AUTO_SUBMITTED);
        assertThat(after.getSubmittedAt()).isEqualTo(after.getEndsAt());
    }

    @Test
    @DisplayName("Unassigned candidate -> 403; contest not ONGOING -> 422; non-candidate -> 403")
    void start_authorizationAndStateGuards() throws Exception {
        Contest ongoing = saveContest(ContestStatus.ONGOING, 60, Instant.now().plus(2, ChronoUnit.HOURS), candidate);
        Contest published = saveContest(ContestStatus.PUBLISHED, 60, Instant.now().plus(2, ChronoUnit.HOURS), candidate);

        mockMvc.perform(post("/api/contests/{id}/session/start", ongoing.getId()).header("Authorization", bearer(otherCandidate)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/contests/{id}/session/start", published.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().is(422));
        mockMvc.perform(post("/api/contests/{id}/session/start", ongoing.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A candidate only ever sees their own session")
    void getSession_isScopedToCurrentCandidate() throws Exception {
        Contest contest = saveContest(ContestStatus.ONGOING, 60, Instant.now().plus(2, ChronoUnit.HOURS), candidate, otherCandidate);
        mockMvc.perform(post("/api/contests/{id}/session/start", contest.getId()).header("Authorization", bearer(candidate)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/contests/{id}/session", contest.getId()).header("Authorization", bearer(otherCandidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_YET_STARTED"));
        mockMvc.perform(post("/api/contests/{id}/session/submit", contest.getId()).header("Authorization", bearer(otherCandidate)))
                .andExpect(status().isNotFound());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private User saveUser(String email, Role role) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash("not-used")
                .fullName("Test " + email)
                .role(role)
                .isActive(true)
                .build());
    }

    private Contest saveContest(ContestStatus status, int durationMinutes, Instant endTime, User... assigned) {
        Contest contest = contestRepository.save(Contest.builder()
                .title("Session test contest")
                .startTime(Instant.now().minus(5, ChronoUnit.MINUTES))
                .endTime(endTime)
                .durationMinutes(durationMinutes)
                .allowedLanguages(List.of("java"))
                .status(status)
                .build());
        for (User user : assigned) {
            contestCandidateRepository.save(ContestCandidate.builder().contest(contest).candidate(user).build());
        }
        return contest;
    }
}
