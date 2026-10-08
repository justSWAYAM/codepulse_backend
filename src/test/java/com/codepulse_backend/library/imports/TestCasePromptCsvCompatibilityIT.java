package com.codepulse_backend.library.imports;

import com.codepulse_backend.auth.service.JwtService;
import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.entity.Subject;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.question.repository.SubjectRepository;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class TestCasePromptCsvCompatibilityIT {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private TestCaseRepository testCaseRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User admin;
    private User evaluator;
    private Subject subject;
    private Question dsaQuestion;
    private Question sqlQuestion;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();

        admin = userRepository.save(User.builder()
                .email("admin-tc@codepulse.dev")
                .passwordHash("not-used")
                .fullName("Admin TC")
                .role(Role.ADMIN)
                .isActive(true)
                .build());

        evaluator = userRepository.save(User.builder()
                .email("eval-tc@codepulse.dev")
                .passwordHash("not-used")
                .fullName("Evaluator TC")
                .role(Role.EVALUATOR)
                .isActive(true)
                .build());

        subject = subjectRepository.save(Subject.builder()
                .name("Algorithms")
                .createdBy(admin.getId())
                .build());

        dsaQuestion = questionRepository.save(Question.builder()
                .title("DSA Library Question")
                .description("Solve algorithmic problem")
                .questionType(QuestionType.DSA)
                .difficulty(Difficulty.MEDIUM)
                .points(10)
                .timeLimitMs(2000)
                .memoryLimitKb(262144)
                .subject(subject)
                .orderIndex(0)
                .build());

        sqlQuestion = questionRepository.save(Question.builder()
                .title("SQL Library Question")
                .description("Query employee data")
                .questionType(QuestionType.SQL)
                .difficulty(Difficulty.EASY)
                .points(10)
                .schemaSql("CREATE TABLE t (id int);")
                .orderMatters(false)
                .subject(subject)
                .orderIndex(0)
                .build());
    }

    @AfterEach
    void cleanUp() {
        testCaseRepository.deleteAll();
        questionRepository.deleteAll();
        subjectRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private byte[] readBytes(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("DSA prompt output CSV imports all 8 rows successfully")
    void dsa_prompt_output_csv_imports_successfully() throws Exception {
        byte[] csvBytes = readBytes("import/dsa-prompt-output.csv");
        MockMultipartFile file = new MockMultipartFile(
                "file", "dsa-prompt-output.csv", "text/csv", csvBytes);

        mockMvc.perform(multipart("/api/questions/{id}/test-cases/bulk", dsaQuestion.getId())
                        .file(file)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(8))
                .andExpect(jsonPath("$.data.succeededCount").value(8))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        List<TestCase> saved = testCaseRepository.findByQuestionIdOrderByOrderIndexAsc(dsaQuestion.getId());
        assertThat(saved).hasSize(8);
        assertThat(saved.get(0).isSample()).isTrue();
        assertThat(saved.get(1).isSample()).isTrue();
        assertThat(saved.get(2).isSample()).isFalse();
    }

    @Test
    @DisplayName("SQL prompt output CSV imports all 6 rows successfully")
    void sql_prompt_output_csv_imports_successfully() throws Exception {
        byte[] csvBytes = readBytes("import/sql-prompt-output.csv");
        MockMultipartFile file = new MockMultipartFile(
                "file", "sql-prompt-output.csv", "text/csv", csvBytes);

        mockMvc.perform(multipart("/api/questions/{id}/test-cases/bulk", sqlQuestion.getId())
                        .file(file)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(6))
                .andExpect(jsonPath("$.data.succeededCount").value(6))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        List<TestCase> saved = testCaseRepository.findByQuestionIdOrderByOrderIndexAsc(sqlQuestion.getId());
        assertThat(saved).hasSize(6);
    }

    @Test
    @DisplayName("negative control: blank input or invalid weight reported by row")
    void negative_control_invalid_row_reported() throws Exception {
        String badCsv = """
                input,expected_output,is_sample,weight
                "valid input","valid output",true,10
                "","missing input",false,10
                "negative weight","output",false,-5
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", badCsv.getBytes());

        mockMvc.perform(multipart("/api/questions/{id}/test-cases/bulk", dsaQuestion.getId())
                        .file(file)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(3))
                .andExpect(jsonPath("$.data.succeededCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(2))
                .andExpect(jsonPath("$.data.errors[0].rowNumber").value(2))
                .andExpect(jsonPath("$.data.errors[1].rowNumber").value(3));

        assertThat(testCaseRepository.findByQuestionIdOrderByOrderIndexAsc(dsaQuestion.getId())).hasSize(1);
    }
}
