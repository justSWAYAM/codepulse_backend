package com.codepulse_backend.question;

import com.codepulse_backend.auth.service.JwtService;
import com.codepulse_backend.common.audit.AuditLogRepository;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class QuestionControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private ContestCandidateRepository contestCandidateRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private TestCaseRepository testCaseRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User admin;
    private User candidate;
    private Contest contest;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        admin = saveUser("admin-q@codepulse.dev", Role.ADMIN);
        candidate = saveUser("cand-q@codepulse.dev", Role.CANDIDATE);
        contest = contestRepository.save(Contest.builder()
                .title("Question test contest")
                .startTime(Instant.now().plus(1, ChronoUnit.DAYS))
                .endTime(Instant.now().plus(2, ChronoUnit.DAYS))
                .durationMinutes(60)
                .allowedLanguages(List.of("java"))
                .status(ContestStatus.DRAFT)
                .build());
    }

    @AfterEach
    void cleanUp() {
        testCaseRepository.deleteAll();
        questionRepository.deleteAll();
        contestCandidateRepository.deleteAll();
        contestRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Reordering by swapping positions succeeds despite the unique (contest_id, order_index) index")
    void reorder_swap_succeeds() throws Exception {
        UUID a = createQuestion("A");
        UUID b = createQuestion("B");
        UUID c = createQuestion("C");

        mockMvc.perform(patch("/api/contests/{id}/questions/reorder", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[\"" + c + "\",\"" + a + "\",\"" + b + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(c.toString()))
                .andExpect(jsonPath("$.data[0].orderIndex").value(1))
                .andExpect(jsonPath("$.data[2].id").value(b.toString()));

        assertThat(questionRepository.findAllByContestIdOrderByOrderIndex(contest.getId()))
                .extracting(Question::getId).containsExactly(c, a, b);
    }

    @Test
    @DisplayName("Reorder with a partial list is rejected with 422, not a DB error")
    void reorder_partialList_isRejected() throws Exception {
        UUID a = createQuestion("A");
        createQuestion("B");

        mockMvc.perform(patch("/api/contests/{id}/questions/reorder", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[\"" + a + "\"]}"))
                .andExpect(status().is(422));
    }

    @Test
    @DisplayName("Deleting a middle question and then creating another does not collide")
    void deleteThenCreate_succeeds() throws Exception {
        createQuestion("A");
        UUID b = createQuestion("B");
        createQuestion("C");

        mockMvc.perform(delete("/api/contests/{cid}/questions/{qid}", contest.getId(), b)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        UUID d = createQuestion("D");
        assertThat(questionRepository.findById(d).orElseThrow().getOrderIndex()).isEqualTo(4);
    }

    @Test
    @DisplayName("Test cases sent with the create request are saved with the question")
    void create_withTestCases_persistsThem() throws Exception {
        String body = questionJson("With cases",
                ",\"testCases\":[{\"input\":\"1 2\",\"expectedOutput\":\"3\",\"isSample\":true,\"weight\":10},"
                        + "{\"input\":\"5 5\",\"expectedOutput\":\"10\",\"isSample\":false,\"weight\":90}]");

        mockMvc.perform(post("/api/contests/{id}/questions", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.testCases.length()").value(2))
                .andExpect(jsonPath("$.data.testCases[0].isSample").value(true));
    }

    @Test
    @DisplayName("Non-admin creating a question -> 403 (not 500)")
    void create_asCandidate_isForbidden() throws Exception {
        mockMvc.perform(post("/api/contests/{id}/questions", contest.getId())
                        .header("Authorization", bearer(candidate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionJson("Nope", "")))
                .andExpect(status().isForbidden());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private UUID createQuestion(String title) throws Exception {
        String response = mockMvc.perform(post("/api/contests/{id}/questions", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionJson(title, "")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.data.id"));
    }

    private String questionJson(String title, String extra) {
        return "{\"title\":\"" + title + "\",\"description\":\"desc\",\"difficulty\":\"EASY\","
                + "\"points\":100,\"timeLimitMs\":2000,\"memoryLimitKb\":262144" + extra + "}";
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
}
