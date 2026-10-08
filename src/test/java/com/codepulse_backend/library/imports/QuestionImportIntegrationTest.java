package com.codepulse_backend.library.imports;

import com.codepulse_backend.auth.service.JwtService;
import com.codepulse_backend.common.audit.AuditLog;
import com.codepulse_backend.common.audit.AuditLogRepository;
import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.exception.BadRequestException;
import com.codepulse_backend.question.dto.CreateLibraryQuestionRequest;
import com.codepulse_backend.question.entity.McqOption;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.entity.Subject;
import com.codepulse_backend.question.repository.McqOptionRepository;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.question.repository.SubjectRepository;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class QuestionImportIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private McqOptionRepository mcqOptionRepository;
    @Autowired private TestCaseRepository testCaseRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockitoSpyBean private QuestionImportRowWriter rowWriter;

    private MockMvc mockMvc;
    private User admin;
    private User evaluatorA;
    private User evaluatorB;
    private User candidate;
    private Subject subject;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();

        admin = saveUser("admin-import@codepulse.dev", "Admin User", Role.ADMIN);
        evaluatorA = saveUser("eval-a-import@codepulse.dev", "Evaluator A", Role.EVALUATOR);
        evaluatorB = saveUser("eval-b-import@codepulse.dev", "Evaluator B", Role.EVALUATOR);
        candidate = saveUser("cand-import@codepulse.dev", "Candidate User", Role.CANDIDATE);

        subject = subjectRepository.save(Subject.builder()
                .name("Computer Science")
                .createdBy(admin.getId())
                .build());
    }

    @AfterEach
    void cleanUp() {
        auditLogRepository.deleteAll();
        mcqOptionRepository.deleteAll();
        testCaseRepository.deleteAll();
        questionRepository.deleteAll();
        subjectRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User saveUser(String email, String fullName, Role role) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash("not-used")
                .fullName(fullName)
                .role(role)
                .isActive(true)
                .build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private String readResource(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mixed 20 rows reports bad rows by number and saves valid rows")
    void mixed_20_rows_reports_bad_rows_by_number() throws Exception {
        String payload = readResource("import/mcq-mixed-20.json");

        mockMvc.perform(post("/api/library/questions/import?dryRun=false")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.result.totalRows").value(20))
                .andExpect(jsonPath("$.data.result.succeededCount").value(14))
                .andExpect(jsonPath("$.data.result.failedCount").value(6))
                .andExpect(jsonPath("$.data.result.errors[0].rowNumber").value(2))
                .andExpect(jsonPath("$.data.result.errors[1].rowNumber").value(4))
                .andExpect(jsonPath("$.data.result.errors[2].rowNumber").value(6))
                .andExpect(jsonPath("$.data.result.errors[3].rowNumber").value(8))
                .andExpect(jsonPath("$.data.result.errors[4].rowNumber").value(10))
                .andExpect(jsonPath("$.data.result.errors[5].rowNumber").value(12));

        List<Question> savedQuestions = questionRepository.findAll();
        assertThat(savedQuestions).hasSize(14);
    }

    @Test
    @DisplayName("dry run persists nothing and returns preview")
    void dry_run_persists_nothing() throws Exception {
        String payload = readResource("import/mcq-mixed-20.json");

        mockMvc.perform(post("/api/library/questions/import?dryRun=true")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.result.totalRows").value(20))
                .andExpect(jsonPath("$.data.result.succeededCount").value(14))
                .andExpect(jsonPath("$.data.result.failedCount").value(6));

        assertThat(questionRepository.count()).isZero();
    }

    @Test
    @DisplayName("default dryRun parameter is true when omitted")
    void default_is_dry_run() throws Exception {
        String payload = readResource("import/dsa-3.json");

        mockMvc.perform(post("/api/library/questions/import")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "DSA",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.result.succeededCount").value(3));

        assertThat(questionRepository.count()).isZero();
    }

    @Test
    @DisplayName("DSA import creates questions without test cases and with subjectId set")
    void dsa_import_has_no_test_cases() throws Exception {
        String payload = readResource("import/dsa-3.json");

        mockMvc.perform(post("/api/library/questions/import?dryRun=false")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "DSA",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.succeededCount").value(3));

        List<Question> questions = questionRepository.findAll();
        assertThat(questions).hasSize(3);
        for (Question q : questions) {
            assertThat(q.getContestId()).isNull();
            assertThat(q.getSubject().getId()).isEqualTo(subject.getId());
            assertThat(q.getSourceQuestion()).isNull();
            assertThat(testCaseRepository.countByQuestionId(q.getId())).isZero();
        }

        // Verify library browsing shows hasNoTestCases = true
        mockMvc.perform(get("/api/library/questions?subjectId=" + subject.getId())
                        .header("Authorization", bearer(evaluatorA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].hasNoTestCases").value(true));
    }

    @Test
    @DisplayName("SQL import stores schemaSql and orderMatters with 0 test cases")
    void sql_import_stores_schema_and_order_flag() throws Exception {
        String payload = readResource("import/sql-2.json");

        mockMvc.perform(post("/api/library/questions/import?dryRun=false")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "SQL",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.succeededCount").value(2));

        List<Question> questions = questionRepository.findAll();
        assertThat(questions).hasSize(2);

        Question q1 = questions.stream().filter(q -> q.getTitle().equals("Find Employees in IT")).findFirst().orElseThrow();
        assertThat(q1.getSchemaSql()).contains("CREATE TABLE employees");
        assertThat(q1.getOrderMatters()).isFalse();
        assertThat(testCaseRepository.countByQuestionId(q1.getId())).isZero();

        Question q2 = questions.stream().filter(q -> q.getTitle().equals("Highest Salary")).findFirst().orElseThrow();
        assertThat(q2.getSchemaSql()).contains("CREATE TABLE salaries");
        assertThat(q2.getOrderMatters()).isTrue();
        assertThat(testCaseRepository.countByQuestionId(q2.getId())).isZero();
    }

    @Test
    @DisplayName("MCQ import preserves options order and correct flags exactly")
    void mcq_import_keeps_options_exactly() throws Exception {
        String payload = """
                [
                  {
                    "title": "MCQ Test",
                    "description": "Select correct options",
                    "difficulty": "EASY",
                    "points": 5,
                    "options": ["Option A", "Option B", "Option C", "Option D"],
                    "correct": [2, 4]
                  }
                ]
                """;

        mockMvc.perform(post("/api/library/questions/import?dryRun=false")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.succeededCount").value(1));

        Question q = questionRepository.findAll().get(0);
        List<McqOption> options = mcqOptionRepository.findByQuestionIdOrderByOrderIndexAsc(q.getId());

        assertThat(options).hasSize(4);
        assertThat(options.get(0).getText()).isEqualTo("Option A");
        assertThat(options.get(0).isCorrect()).isFalse();
        assertThat(options.get(0).getOrderIndex()).isEqualTo(1);

        assertThat(options.get(1).getText()).isEqualTo("Option B");
        assertThat(options.get(1).isCorrect()).isTrue();
        assertThat(options.get(1).getOrderIndex()).isEqualTo(2);

        assertThat(options.get(2).getText()).isEqualTo("Option C");
        assertThat(options.get(2).isCorrect()).isFalse();
        assertThat(options.get(2).getOrderIndex()).isEqualTo(3);

        assertThat(options.get(3).getText()).isEqualTo("Option D");
        assertThat(options.get(3).isCorrect()).isTrue();
        assertThat(options.get(3).getOrderIndex()).isEqualTo(4);
    }

    @Test
    @DisplayName("THEORY import allows optional modelAnswer")
    void theory_import_model_answer_optional() throws Exception {
        String payload = """
                [
                  {"title":"Q1 with Answer","description":"D1","difficulty":"MEDIUM","points":10,"modelAnswer":"Detailed explanation"},
                  {"title":"Q2 without Answer","description":"D2","difficulty":"EASY","points":10}
                ]
                """;

        mockMvc.perform(post("/api/library/questions/import?dryRun=false")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "THEORY",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.succeededCount").value(2));

        List<Question> questions = questionRepository.findAll();
        Question q1 = questions.stream().filter(q -> q.getTitle().equals("Q1 with Answer")).findFirst().orElseThrow();
        assertThat(q1.getModelAnswer()).isEqualTo("Detailed explanation");

        Question q2 = questions.stream().filter(q -> q.getTitle().equals("Q2 without Answer")).findFirst().orElseThrow();
        assertThat(q2.getModelAnswer()).isNull();
    }

    @Test
    @DisplayName("author is the importing user")
    void author_is_importer() throws Exception {
        String payload = """
                [
                  {"title":"Evaluator B Question","description":"Desc","difficulty":"EASY","points":5,"options":["A","B"],"correct":[1]}
                ]
                """;

        mockMvc.perform(post("/api/library/questions/import?dryRun=false")
                        .header("Authorization", bearer(evaluatorB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isOk());

        Question q = questionRepository.findAll().get(0);
        assertThat(q.getCreatedBy()).isEqualTo(evaluatorB.getId());

        mockMvc.perform(get("/api/library/questions?subjectId=" + subject.getId())
                        .header("Authorization", bearer(evaluatorA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].authorName").value("Evaluator B"));
    }

    @Test
    @DisplayName("candidate receives 403 on both endpoints")
    void candidate_gets_403() throws Exception {
        mockMvc.perform(get("/api/library/import-template?type=MCQ")
                        .header("Authorization", bearer(candidate)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/library/questions/import")
                        .header("Authorization", bearer(candidate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", "[]"
                        ))))
                .andExpect(status().isForbidden());

        assertThat(questionRepository.count()).isZero();
    }

    @Test
    @DisplayName("anonymous receives 401 on both endpoints")
    void anonymous_gets_401() throws Exception {
        mockMvc.perform(get("/api/library/import-template?type=MCQ"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/library/questions/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", "[]"
                        ))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unknown subject returns 404 SUBJECT_NOT_FOUND before parsing")
    void unknown_subject_404() throws Exception {
        UUID unknownId = UUID.randomUUID();

        mockMvc.perform(post("/api/library/questions/import")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", unknownId,
                                "type", "MCQ",
                                "payload", "not even valid json"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SUBJECT_NOT_FOUND")));
    }

    @Test
    @DisplayName("invalid type returns 400 Bad Request")
    void invalid_type_400() throws Exception {
        mockMvc.perform(get("/api/library/import-template?type=UNKNOWN")
                        .header("Authorization", bearer(evaluatorA)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/library/questions/import")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + subject.getId() + "\",\"type\":\"UNKNOWN\",\"payload\":\"[]\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("malformed JSON payload returns 400 IMPORT_PAYLOAD_INVALID with line/column")
    void malformed_json_400() throws Exception {
        mockMvc.perform(post("/api/library/questions/import")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", "[{\"title\":"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("IMPORT_PAYLOAD_INVALID")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("line")));
    }

    @Test
    @DisplayName("over 200 questions returns 400 IMPORT_LIMIT_EXCEEDED")
    void over_200_400() throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"title\":\"Q").append(i).append("\"}");
        }
        sb.append("]");

        mockMvc.perform(post("/api/library/questions/import")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", sb.toString()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("IMPORT_LIMIT_EXCEEDED")));

        assertThat(questionRepository.count()).isZero();
    }

    @Test
    @DisplayName("payload larger than 1 MB returns 413 IMPORT_PAYLOAD_TOO_LARGE")
    void over_1mb_413() throws Exception {
        String hugeString = "a".repeat(1048577);
        String payload = "[{\"title\":\"" + hugeString + "\"}]";

        mockMvc.perform(post("/api/library/questions/import")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("IMPORT_PAYLOAD_TOO_LARGE")));
    }

    @Test
    @DisplayName("one failing save does not poison batch or rollback other rows")
    void one_failing_save_does_not_poison_batch() throws Exception {
        doAnswer(invocation -> {
            CreateLibraryQuestionRequest req = invocation.getArgument(0);
            if ("Failing Row 2".equals(req.title())) {
                throw new BadRequestException("Simulated save failure for row 2");
            }
            return invocation.callRealMethod();
        }).when(rowWriter).write(any());

        String payload = """
                [
                  {"title":"Good Row 1","description":"D1","difficulty":"EASY","points":5,"options":["A","B"],"correct":[1]},
                  {"title":"Failing Row 2","description":"D2","difficulty":"EASY","points":5,"options":["A","B"],"correct":[1]},
                  {"title":"Good Row 3","description":"D3","difficulty":"EASY","points":5,"options":["A","B"],"correct":[1]}
                ]
                """;

        mockMvc.perform(post("/api/library/questions/import?dryRun=false")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.totalRows").value(3))
                .andExpect(jsonPath("$.data.result.succeededCount").value(2))
                .andExpect(jsonPath("$.data.result.failedCount").value(1))
                .andExpect(jsonPath("$.data.result.errors[0].rowNumber").value(2))
                .andExpect(jsonPath("$.data.result.errors[0].reason").value("Simulated save failure for row 2"));

        List<Question> saved = questionRepository.findAll();
        assertThat(saved).hasSize(2);
        assertThat(saved.stream().map(Question::getTitle))
                .containsExactlyInAnyOrder("Good Row 1", "Good Row 3");
    }

    @Test
    @DisplayName("real import is audited, dry run is not")
    void import_is_audited() throws Exception {
        String payload = """
                [
                  {"title":"Audited Question","description":"D","difficulty":"EASY","points":5,"options":["A","B"],"correct":[1]}
                ]
                """;

        // Dry run creates no audit
        mockMvc.perform(post("/api/library/questions/import?dryRun=true")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isOk());

        assertThat(auditLogRepository.count()).isZero();

        // Real import creates audit
        mockMvc.perform(post("/api/library/questions/import?dryRun=false")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isOk());

        List<AuditLog> logs = auditLogRepository.findAll();
        assertThat(logs).hasSize(1);
        AuditLog log = logs.get(0);
        assertThat(log.getAction()).isEqualTo("LIBRARY_QUESTIONS_IMPORTED");
        assertThat(log.getEntityType()).isEqualTo("SUBJECT");
        assertThat(log.getEntityId()).isEqualTo(subject.getId());
        assertThat(log.getActorId()).isEqualTo(evaluatorA.getId());
        assertThat(log.getDetails()).contains("type=MCQ, imported=1, failed=0");
    }

    @Test
    @DisplayName("real LLM Claude MCQ fixture imports with 0 errors in dry run")
    void real_llm_fixtures_claude_mcq() throws Exception {
        String payload = readResource("import/llm-claude-mcq.json");

        mockMvc.perform(post("/api/library/questions/import?dryRun=true")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.totalRows").value(2))
                .andExpect(jsonPath("$.data.result.succeededCount").value(2))
                .andExpect(jsonPath("$.data.result.failedCount").value(0))
                .andExpect(jsonPath("$.data.result.errors").isEmpty());
    }

    @Test
    @DisplayName("real LLM Gemini MCQ fixture imports with 0 errors in dry run")
    void real_llm_fixtures_gemini_mcq() throws Exception {
        String payload = readResource("import/llm-gemini-mcq.json");

        mockMvc.perform(post("/api/library/questions/import?dryRun=true")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "MCQ",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.totalRows").value(2))
                .andExpect(jsonPath("$.data.result.succeededCount").value(2))
                .andExpect(jsonPath("$.data.result.failedCount").value(0))
                .andExpect(jsonPath("$.data.result.errors").isEmpty());
    }

    @Test
    @DisplayName("real LLM Claude SQL fixture imports with 0 errors in dry run")
    void real_llm_fixtures_claude_sql() throws Exception {
        String payload = readResource("import/llm-claude-sql.json");

        mockMvc.perform(post("/api/library/questions/import?dryRun=true")
                        .header("Authorization", bearer(evaluatorA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectId", subject.getId(),
                                "type", "SQL",
                                "payload", payload
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.totalRows").value(1))
                .andExpect(jsonPath("$.data.result.succeededCount").value(1))
                .andExpect(jsonPath("$.data.result.failedCount").value(0))
                .andExpect(jsonPath("$.data.result.errors").isEmpty());
    }

    @Test
    @DisplayName("GET /api/library/import-template returns prompt and metadata")
    void template_endpoint_evaluator_200() throws Exception {
        mockMvc.perform(get("/api/library/import-template?type=DSA")
                        .header("Authorization", bearer(evaluatorA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("DSA"))
                .andExpect(jsonPath("$.data.maxQuestions").value(200))
                .andExpect(jsonPath("$.data.maxPayloadBytes").value(1048576))
                .andExpect(jsonPath("$.data.prompt").value(org.hamcrest.Matchers.containsString("Return ONLY the JSON")));
    }
}
