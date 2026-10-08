package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.library.imports.config.ImportProperties;
import com.codepulse_backend.library.imports.dto.ImportQuestionItem;
import com.codepulse_backend.question.dto.CreateLibraryQuestionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionImportMapperTest {

    private QuestionImportMapper mapper;
    private ImportProperties props;
    private final UUID subjectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = new ImportProperties(1048576L, 200, 2000, 262144);
        mapper = new QuestionImportMapper(props);
    }

    @Test
    @DisplayName("DSA limits use defaults when missing, or preserve provided values")
    void dsaLimitsMapping() {
        ImportQuestionItem missingLimits = new ImportQuestionItem(
                "Title", "Description", "MEDIUM", 10, null, null, null, null, null, null, null);
        CreateLibraryQuestionRequest req1 = mapper.toRequest(QuestionType.DSA, subjectId, missingLimits);
        assertThat(req1.timeLimitMs()).isEqualTo(2000);
        assertThat(req1.memoryLimitKb()).isEqualTo(262144);
        assertThat(req1.difficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(req1.subjectId()).isEqualTo(subjectId);
        assertThat(req1.questionType()).isEqualTo(QuestionType.DSA);

        ImportQuestionItem explicitLimits = new ImportQuestionItem(
                "Title", "Description", "HARD", 10, 3000, 524288, null, null, null, null, null);
        CreateLibraryQuestionRequest req2 = mapper.toRequest(QuestionType.DSA, subjectId, explicitLimits);
        assertThat(req2.timeLimitMs()).isEqualTo(3000);
        assertThat(req2.memoryLimitKb()).isEqualTo(524288);
    }

    @Test
    @DisplayName("MCQ options map 1-based correct indexes to isCorrect flags")
    void mcqOptionsMapping() {
        ImportQuestionItem mcq = new ImportQuestionItem(
                "Which keyword?", "Description", "EASY", 5, null, null, null, null,
                List.of("var", "let", "const", "def"), List.of(2, 3), null);

        CreateLibraryQuestionRequest req = mapper.toRequest(QuestionType.MCQ, subjectId, mcq);

        assertThat(req.options()).hasSize(4);
        assertThat(req.options().get(0).text()).isEqualTo("var");
        assertThat(req.options().get(0).isCorrect()).isFalse();

        assertThat(req.options().get(1).text()).isEqualTo("let");
        assertThat(req.options().get(1).isCorrect()).isTrue();

        assertThat(req.options().get(2).text()).isEqualTo("const");
        assertThat(req.options().get(2).isCorrect()).isTrue();

        assertThat(req.options().get(3).text()).isEqualTo("def");
        assertThat(req.options().get(3).isCorrect()).isFalse();
    }

    @Test
    @DisplayName("SQL mapping: schemaSql preserved, orderMatters null becomes false")
    void sqlMapping() {
        ImportQuestionItem sql = new ImportQuestionItem(
                "SQL Query", "Description", "EASY", 10, null, null,
                "CREATE TABLE test (id int);", null, null, null, null);

        CreateLibraryQuestionRequest req = mapper.toRequest(QuestionType.SQL, subjectId, sql);
        assertThat(req.schemaSql()).isEqualTo("CREATE TABLE test (id int);");
        assertThat(req.orderMatters()).isFalse();
    }

    @Test
    @DisplayName("THEORY mapping: blank modelAnswer becomes null")
    void theoryMapping() {
        ImportQuestionItem itemBlank = new ImportQuestionItem(
                "Title", "Description", "EASY", 10, null, null, null, null, null, null, "   ");
        CreateLibraryQuestionRequest req1 = mapper.toRequest(QuestionType.THEORY, subjectId, itemBlank);
        assertThat(req1.modelAnswer()).isNull();

        ImportQuestionItem itemValid = new ImportQuestionItem(
                "Title", "Description", "EASY", 10, null, null, null, null, null, null, "Sample Answer");
        CreateLibraryQuestionRequest req2 = mapper.toRequest(QuestionType.THEORY, subjectId, itemValid);
        assertThat(req2.modelAnswer()).isEqualTo("Sample Answer");
    }

    @Test
    @DisplayName("detail strings for all question types")
    void detailStrings() {
        ImportQuestionItem dsa = new ImportQuestionItem("T", "D", "EASY", 10, 1500, 131072, null, null, null, null, null);
        assertThat(mapper.detail(QuestionType.DSA, dsa)).isEqualTo("time 1500 ms · memory 131072 KB");

        ImportQuestionItem sql = new ImportQuestionItem("T", "D", "EASY", 10, null, null, "CREATE TABLE a; INSERT INTO a;", true, null, null, null);
        assertThat(mapper.detail(QuestionType.SQL, sql)).isEqualTo("schema: 2 statements · orderMatters=true");

        ImportQuestionItem mcq = new ImportQuestionItem("T", "D", "EASY", 5, null, null, null, null, List.of("A", "B"), List.of(1), null);
        assertThat(mapper.detail(QuestionType.MCQ, mcq)).isEqualTo("2 options · correct: 1");

        ImportQuestionItem theory = new ImportQuestionItem("T", "D", "EASY", 10, null, null, null, null, null, null, "Answer");
        assertThat(mapper.detail(QuestionType.THEORY, theory)).isEqualTo("model answer: yes");
    }

    @Test
    @DisplayName("descriptionPreview collapses whitespace and truncates at 160 characters")
    void descriptionPreview() {
        String longText = "a".repeat(200);
        String preview = mapper.descriptionPreview(longText);
        assertThat(preview).hasSize(161); // 160 chars + "…"
        assertThat(preview).endsWith("…");

        String whitespaceText = "Line 1\n\nLine 2 \t Line 3";
        assertThat(mapper.descriptionPreview(whitespaceText)).isEqualTo("Line 1 Line 2 Line 3");
    }
}
