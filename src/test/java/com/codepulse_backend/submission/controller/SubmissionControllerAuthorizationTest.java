package com.codepulse_backend.submission.controller;

import com.codepulse_backend.auth.service.JwtService;
import com.codepulse_backend.common.audit.AuditLogRepository;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.common.enums.TestCaseResultStatus;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 8 object-level access rules on the submission endpoints:
 * role checks on run/submit, 404 for someone else's submission, the
 * candidate/evaluator view split and the hidden-test-case boundary.
 */
@SpringBootTest
@ActiveProfiles("test")
class SubmissionControllerAuthorizationTest {

    private static final String HIDDEN_INPUT = "hidden-input-7d1f";
    private static final String HIDDEN_EXPECTED = "hidden-expected-7d1f";
    private static final String HIDDEN_ACTUAL = "hidden-actual-7d1f";
    private static final String SAMPLE_EXPECTED = "sample-expected-7d1f";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private AssessmentSessionRepository sessionRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private SubmissionTestCaseResultRepository resultRepository;
    @Autowired private TestCaseRepository testCaseRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User candidate;
    private User otherCandidate;
    private User evaluator;
    private User admin;
    private AssessmentSession session;
    private Submission submission;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();

        candidate = saveUser("sub-cand@codepulse.dev", Role.CANDIDATE);
        otherCandidate = saveUser("sub-other@codepulse.dev", Role.CANDIDATE);
        evaluator = saveUser("sub-eval@codepulse.dev", Role.EVALUATOR);
        admin = saveUser("sub-admin@codepulse.dev", Role.ADMIN);

        session = new AssessmentSession();
        session.setContestId(UUID.randomUUID());
        session.setCandidateId(candidate.getId());
        session.setStartedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        session.setEndsAt(Instant.now().plus(50, ChronoUnit.MINUTES));
        session.setStatus(SessionStatus.IN_PROGRESS);
        session = sessionRepository.save(session);

        UUID questionId = UUID.randomUUID();
        TestCase sample = saveTestCase(questionId, true, "1 2", SAMPLE_EXPECTED, 1);
        TestCase hidden = saveTestCase(questionId, false, HIDDEN_INPUT, HIDDEN_EXPECTED, 2);

        Submission s = new Submission();
        s.setSessionId(session.getId());
        s.setQuestionId(questionId);
        s.setCandidateId(candidate.getId());
        s.setLanguage("JAVA");
        s.setSourceCode("class Main {}");
        s.setSubmissionType(SubmissionType.SUBMIT);
        s.setStatus(SubmissionStatus.WRONG_ANSWER);
        s.setScore(new BigDecimal("50.00"));
        s.setPassedCount(1);
        s.setTotalCount(2);
        s.setSubmittedAt(Instant.now());
        s.setEvaluatedAt(Instant.now());
        submission = submissionRepository.save(s);

        resultRepository.save(result(sample, TestCaseResultStatus.PASSED, "3"));
        resultRepository.save(result(hidden, TestCaseResultStatus.WRONG_ANSWER, HIDDEN_ACTUAL));
    }

    @AfterEach
    void cleanUp() {
        resultRepository.deleteAll();
        submissionRepository.deleteAll();
        testCaseRepository.deleteAll();
        sessionRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Only candidates may Run or Submit")
    void runAndSubmit_requireCandidateRole() throws Exception {
        String body = "{\"questionId\":\"" + UUID.randomUUID()
                + "\",\"language\":\"JAVA\",\"sourceCode\":\"class Main {}\"}";

        mockMvc.perform(post("/api/submissions/run").header("Authorization", bearer(evaluator))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/submissions/submit").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/submissions/run")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Another candidate's submission, or a missing one, is 404 (no ID probing)")
    void otherCandidatesSubmission_isNotFound() throws Exception {
        mockMvc.perform(get("/api/submissions/{id}", submission.getId())
                        .header("Authorization", bearer(otherCandidate)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/submissions/{id}", UUID.randomUUID())
                        .header("Authorization", bearer(candidate)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Owner sees the candidate view: samples in full, hidden cases only as a count")
    void owner_getsCandidateViewWithoutHiddenData() throws Exception {
        String json = mockMvc.perform(get("/api/submissions/{id}", submission.getId())
                        .header("Authorization", bearer(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WRONG_ANSWER"))
                .andExpect(jsonPath("$.sourceCode").value("class Main {}"))
                .andExpect(jsonPath("$.sampleResults.length()").value(1))
                .andExpect(jsonPath("$.hiddenSummary.passed").value(0))
                .andExpect(jsonPath("$.hiddenSummary.total").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(json)
                .doesNotContain("expectedOutput")
                .doesNotContain(SAMPLE_EXPECTED)
                .doesNotContain(HIDDEN_INPUT)
                .doesNotContain(HIDDEN_EXPECTED)
                .doesNotContain(HIDDEN_ACTUAL);
    }

    @Test
    @DisplayName("After the session ends the candidate no longer sees verdicts or results")
    void owner_afterSessionEnds_resultsHidden() throws Exception {
        session.setStatus(SessionStatus.SUBMITTED);
        sessionRepository.save(session);

        mockMvc.perform(get("/api/submissions/{id}", submission.getId())
                        .header("Authorization", bearer(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCode").value("class Main {}"))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.score").doesNotExist())
                .andExpect(jsonPath("$.sampleResults").doesNotExist())
                .andExpect(jsonPath("$.hiddenSummary").doesNotExist());
    }

    @Test
    @DisplayName("Evaluators and admins get the full evaluator view, hidden cases included")
    void evaluatorAndAdmin_getEvaluatorView() throws Exception {
        for (User staff : new User[]{evaluator, admin}) {
            String json = mockMvc.perform(get("/api/submissions/{id}", submission.getId())
                            .header("Authorization", bearer(staff)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.candidateId").value(candidate.getId().toString()))
                    .andExpect(jsonPath("$.testCaseResults.length()").value(2))
                    .andReturn().getResponse().getContentAsString();

            assertThat(json).contains(HIDDEN_EXPECTED).contains(HIDDEN_INPUT);
        }
    }

    @Test
    @DisplayName("Candidates cannot list contest submissions or rejudge")
    void candidate_cannotUseEvaluatorEndpoints() throws Exception {
        mockMvc.perform(get("/api/contests/{id}/submissions", session.getContestId())
                        .header("Authorization", bearer(candidate)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/submissions/{id}/rejudge", submission.getId())
                        .header("Authorization", bearer(candidate)))
                .andExpect(status().isForbidden());
    }

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

    private TestCase saveTestCase(UUID questionId, boolean sample, String input, String expected, int order) {
        TestCase testCase = new TestCase();
        testCase.setQuestionId(questionId);
        testCase.setInput(input);
        testCase.setExpectedOutput(expected);
        testCase.setSample(sample);
        testCase.setWeight(1);
        testCase.setOrderIndex(order);
        return testCaseRepository.save(testCase);
    }

    private SubmissionTestCaseResult result(TestCase testCase, TestCaseResultStatus status, String actual) {
        SubmissionTestCaseResult result = new SubmissionTestCaseResult();
        result.setSubmission(submission);
        result.setTestCaseId(testCase.getId());
        result.setStatus(status);
        result.setActualOutput(actual);
        result.setWeight(testCase.getWeight());
        result.setSample(testCase.isSample());
        return result;
    }
}
