package com.codepulse_backend.question;

import com.codepulse_backend.auth.service.JwtService;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.question.entity.McqOption;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.entity.Subject;
import com.codepulse_backend.question.repository.McqOptionRepository;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.question.repository.SubjectRepository;
import com.codepulse_backend.testcase.entity.TestCase;
import com.codepulse_backend.testcase.repository.TestCaseRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.common.enums.Difficulty;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Module 5A – Question Library.
 *
 * Tests cover:
 * 1. Subject folder creation and listing.
 * 2. Library question creation (as Evaluator).
 * 3. Listing library questions with filters.
 * 4. Candidate 403 lockout on all /api/library/** endpoints.
 * 5. Deep-copy: add library questions to a contest.
 * 6. Independence guarantee: editing library question does not affect copy.
 * 7. Duplicate-copy idempotency.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuestionLibraryIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private ContestCandidateRepository contestCandidateRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private McqOptionRepository mcqOptionRepository;
    @Autowired private TestCaseRepository testCaseRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User admin;
    private User evaluator;
    private User candidate;
    private Contest contest;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        admin     = saveUser("admin-lib@codepulse.dev",     Role.ADMIN);
        evaluator = saveUser("eval-lib@codepulse.dev",      Role.EVALUATOR);
        candidate = saveUser("cand-lib@codepulse.dev",      Role.CANDIDATE);
        contest = contestRepository.save(Contest.builder()
                .title("Library test contest")
                .startTime(Instant.now().plus(1, ChronoUnit.DAYS))
                .endTime(Instant.now().plus(2, ChronoUnit.DAYS))
                .durationMinutes(60)
                .allowedLanguages(List.of("java"))
                .status(ContestStatus.DRAFT)
                .build());
    }

    @AfterEach
    void cleanUp() {
        mcqOptionRepository.deleteAll();
        testCaseRepository.deleteAll();
        questionRepository.deleteAll();
        subjectRepository.deleteAll();
        contestCandidateRepository.deleteAll();
        contestRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ─── Subject CRUD ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Evaluator can create and list subjects")
    void evaluatorCanCreateAndListSubjects() throws Exception {
        // Create
        mockMvc.perform(post("/api/library/subjects")
                        .header("Authorization", bearer(evaluator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Data Structures\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Data Structures"))
                .andExpect(jsonPath("$.data.id").exists());

        // List
        mockMvc.perform(get("/api/library/subjects")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("Duplicate subject name returns 409")
    void duplicateSubjectNameReturns409() throws Exception {
        subjectRepository.save(Subject.builder()
                .name("Algorithms")
                .createdBy(admin.getId())
                .build());

        mockMvc.perform(post("/api/library/subjects")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"algorithms\"}"))  // case-insensitive duplicate
                .andExpect(status().isConflict());
    }

    // ─── Library question CRUD ───────────────────────────────────────────────

    @Test
    @DisplayName("Evaluator can create a library DSA question")
    void evaluatorCanCreateLibraryQuestion() throws Exception {
        Subject subject = subjectRepository.save(Subject.builder()
                .name("DSA")
                .createdBy(admin.getId())
                .build());

        String body = """
                {
                  "subjectId": "%s",
                  "questionType": "DSA",
                  "title": "Two Sum",
                  "description": "Find two numbers that add up to target.",
                  "difficulty": "EASY",
                  "points": 100,
                  "timeLimitMs": 2000,
                  "memoryLimitKb": 262144
                }
                """.formatted(subject.getId());

        mockMvc.perform(post("/api/library/questions")
                        .header("Authorization", bearer(evaluator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("Two Sum"))
                .andExpect(jsonPath("$.data.subjectId").value(subject.getId().toString()))
                .andExpect(jsonPath("$.data.hasNoTestCases").value(true));
    }

    @Test
    @DisplayName("Library questions list returns correct paged data")
    void listLibraryQuestionsIsPaged() throws Exception {
        Subject subject = subjectRepository.save(Subject.builder()
                .name("SQL").createdBy(admin.getId()).build());

        // Save two library questions directly with a createdBy user
        questionRepository.save(buildLibraryQuestion("Q1", subject, QuestionType.DSA, admin));
        questionRepository.save(buildLibraryQuestion("Q2", subject, QuestionType.SQL, evaluator));

        // Unfiltered
        mockMvc.perform(get("/api/library/questions")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        // Filtered by type
        mockMvc.perform(get("/api/library/questions?type=DSA")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Q1"));
    }

    // ─── Candidate lockout ───────────────────────────────────────────────────

    @Test
    @DisplayName("Candidate receives 403 on all /api/library/** endpoints")
    void candidateIsLockedOutOfLibrary() throws Exception {
        mockMvc.perform(get("/api/library/subjects")
                        .header("Authorization", bearer(candidate)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/library/questions")
                        .header("Authorization", bearer(candidate)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/library/subjects")
                        .header("Authorization", bearer(candidate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hack\"}"))
                .andExpect(status().isForbidden());
    }

    // ─── Deep-copy: add from library ─────────────────────────────────────────

    @Test
    @DisplayName("Adding library questions deep-copies them into the contest")
    void addFromLibraryCreatesIndependentCopies() throws Exception {
        Subject subject = subjectRepository.save(Subject.builder()
                .name("Algorithms").createdBy(admin.getId()).build());

        Question libQ = questionRepository.save(buildLibraryQuestion("BFS", subject, QuestionType.DSA, admin));
        // Add 2 test cases to the library question
        testCaseRepository.save(TestCase.builder()
                .questionId(libQ.getId()).input("3").expectedOutput("6")
                .isSample(true).weight(1).orderIndex(1).build());
        testCaseRepository.save(TestCase.builder()
                .questionId(libQ.getId()).input("5").expectedOutput("10")
                .isSample(false).weight(2).orderIndex(2).build());

        String body = "{\"questionIds\":[\"%s\"]}".formatted(libQ.getId());

        mockMvc.perform(post("/api/contests/{id}/questions/from-library", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Verify a copy was created inside the contest
        List<Question> contestQuestions =
                questionRepository.findAllByContestIdOrderByOrderIndex(contest.getId());
        assertThat(contestQuestions).hasSize(1);
        Question copy = contestQuestions.get(0);
        assertThat(copy.getTitle()).isEqualTo("BFS");
        assertThat(copy.getSourceQuestion().getId()).isEqualTo(libQ.getId());

        // Verify test cases were copied
        List<TestCase> copiedTcs = testCaseRepository.findByQuestionIdOrderByOrderIndexAsc(copy.getId());
        assertThat(copiedTcs).hasSize(2);

        // Verify library original still exists untouched
        assertThat(questionRepository.findById(libQ.getId())).isPresent();
        assertThat(testCaseRepository.findByQuestionIdOrderByOrderIndexAsc(libQ.getId())).hasSize(2);
    }

    @Test
    @DisplayName("Adding same library question twice is idempotent")
    void addFromLibraryIsIdempotent() throws Exception {
        Subject subject = subjectRepository.save(Subject.builder()
                .name("Graph").createdBy(admin.getId()).build());
        Question libQ = questionRepository.save(buildLibraryQuestion("DFS", subject, QuestionType.DSA, admin));

        String body = "{\"questionIds\":[\"%s\"]}".formatted(libQ.getId());

        // First call
        mockMvc.perform(post("/api/contests/{id}/questions/from-library", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Second call — same question ID
        mockMvc.perform(post("/api/contests/{id}/questions/from-library", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Should still only be one copy
        List<Question> contestQuestions =
                questionRepository.findAllByContestIdOrderByOrderIndex(contest.getId());
        assertThat(contestQuestions).hasSize(1);
    }

    @Test
    @DisplayName("Editing library question after copy does not affect contest copy")
    void editingLibraryQuestionDoesNotAffectContestCopy() throws Exception {
        Subject subject = subjectRepository.save(Subject.builder()
                .name("Math").createdBy(admin.getId()).build());
        Question libQ = questionRepository.save(buildLibraryQuestion("Original Title", subject, QuestionType.DSA, admin));

        // Copy into contest
        String copyBody = "{\"questionIds\":[\"%s\"]}".formatted(libQ.getId());
        mockMvc.perform(post("/api/contests/{id}/questions/from-library", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(copyBody))
                .andExpect(status().isOk());

        // Edit the library question
        String editBody = "{\"title\":\"Changed Title\"}";
        mockMvc.perform(put("/api/library/questions/{id}", libQ.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Changed Title"));

        // Contest copy must still have the original title
        List<Question> contestQuestions =
                questionRepository.findAllByContestIdOrderByOrderIndex(contest.getId());
        assertThat(contestQuestions).hasSize(1);
        assertThat(contestQuestions.get(0).getTitle()).isEqualTo("Original Title");
    }

    // ─── MCQ options deep-copy ───────────────────────────────────────────────

    @Test
    @DisplayName("MCQ library question deep-copies its options")
    void mcqLibraryQuestionCopiesOptions() throws Exception {
        Subject subject = subjectRepository.save(Subject.builder()
                .name("Aptitude").createdBy(admin.getId()).build());
        Question libMcq = questionRepository.save(Question.builder()
                .contestId(null).subject(subject)
                .questionType(QuestionType.MCQ)
                .title("Capital of France")
                .description("What is the capital of France?")
                .difficulty(Difficulty.EASY)
                .points(10).timeLimitMs(2000).memoryLimitKb(262144).orderIndex(0)
                .build());

        // Save options directly
        mcqOptionRepository.save(McqOption.builder().questionId(libMcq.getId())
                .text("Paris").isCorrect(true).orderIndex(1).build());
        mcqOptionRepository.save(McqOption.builder().questionId(libMcq.getId())
                .text("London").isCorrect(false).orderIndex(2).build());

        String body = "{\"questionIds\":[\"%s\"]}".formatted(libMcq.getId());
        mockMvc.perform(post("/api/contests/{id}/questions/from-library", contest.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Verify copied question
        List<Question> copied =
                questionRepository.findAllByContestIdOrderByOrderIndex(contest.getId());
        assertThat(copied).hasSize(1);

        // Verify options were deep-copied
        List<McqOption> copiedOptions =
                mcqOptionRepository.findByQuestionIdOrderByOrderIndexAsc(copied.get(0).getId());
        assertThat(copiedOptions).hasSize(2);
        assertThat(copiedOptions.get(0).getText()).isEqualTo("Paris");
        assertThat(copiedOptions.get(0).isCorrect()).isTrue();

        // Original options unaffected
        assertThat(mcqOptionRepository.findByQuestionIdOrderByOrderIndexAsc(libMcq.getId()))
                .hasSize(2);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Question buildLibraryQuestion(String title, Subject subject, QuestionType type, User author) {
        return Question.builder()
                .contestId(null)
                .subject(subject)
                .questionType(type)
                .title(title)
                .description("Library question: " + title)
                .difficulty(Difficulty.MEDIUM)
                .points(100)
                .timeLimitMs(2000)
                .memoryLimitKb(262144)
                .orderIndex(0)
                .build();
    }

    /**
     * Convenience overload — uses admin as the default author.
     * NOTE: BaseEntity.createdBy is set by JPA Auditing from the security context.
     * Tests that bypass the API must save via the full HTTP stack (POST /api/library/questions)
     * or accept that createdBy may be null (admin can still edit any question).
     */
    private Question buildLibraryQuestion(String title, Subject subject, QuestionType type) {
        return buildLibraryQuestion(title, subject, type, null);
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
