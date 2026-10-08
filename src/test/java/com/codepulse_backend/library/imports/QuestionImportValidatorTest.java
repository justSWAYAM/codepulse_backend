package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.library.imports.dto.ImportQuestionItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionImportValidatorTest {

    private QuestionImportValidator validator;

    @BeforeEach
    void setUp() {
        validator = new QuestionImportValidator();
    }

    @Test
    @DisplayName("valid DSA question returns no errors")
    void validDsaQuestion() {
        ImportQuestionItem item = new ImportQuestionItem(
                "Two Sum", "Find two numbers", "EASY", 10, 2000, 262144,
                null, null, null, null, null);
        List<String> errors = validator.validateItem(QuestionType.DSA, item);
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("missing title and description returns both errors")
    void missingTitleAndDescription() {
        ImportQuestionItem item = new ImportQuestionItem(
                "", null, "EASY", 10, null, null, null, null, null, null, null);
        List<String> errors = validator.validateItem(QuestionType.DSA, item);
        assertThat(errors).contains("title is required", "description is required");
    }

    @Test
    @DisplayName("difficulty casing and trimming: 'easy' and ' Hard ' pass")
    void difficultyCasingAndTrimming() {
        ImportQuestionItem item1 = new ImportQuestionItem(
                "Q1", "Desc", "easy", 10, null, null, null, null, null, null, null);
        assertThat(validator.validateItem(QuestionType.DSA, item1)).isEmpty();

        ImportQuestionItem item2 = new ImportQuestionItem(
                "Q2", "Desc", " Hard ", 10, null, null, null, null, null, null, null);
        assertThat(validator.validateItem(QuestionType.DSA, item2)).isEmpty();
    }

    @Test
    @DisplayName("invalid difficulty returns friendly error")
    void invalidDifficulty() {
        ImportQuestionItem item = new ImportQuestionItem(
                "Q1", "Desc", "SUPER_HARD", 10, null, null, null, null, null, null, null);
        List<String> errors = validator.validateItem(QuestionType.DSA, item);
        assertThat(errors).contains("difficulty must be EASY, MEDIUM or HARD (got 'SUPER_HARD')");
    }

    @Test
    @DisplayName("points null or <= 0 returns error")
    void pointsValidation() {
        ImportQuestionItem nullPoints = new ImportQuestionItem(
                "Q1", "Desc", "EASY", null, null, null, null, null, null, null, null);
        assertThat(validator.validateItem(QuestionType.DSA, nullPoints)).contains("points is required");

        ImportQuestionItem zeroPoints = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 0, null, null, null, null, null, null, null);
        assertThat(validator.validateItem(QuestionType.DSA, zeroPoints)).contains("points must be greater than 0");

        ImportQuestionItem negativePoints = new ImportQuestionItem(
                "Q1", "Desc", "EASY", -5, null, null, null, null, null, null, null);
        assertThat(validator.validateItem(QuestionType.DSA, negativePoints)).contains("points must be greater than 0");
    }

    @Test
    @DisplayName("DSA limits <= 0 return errors")
    void dsaLimitsValidation() {
        ImportQuestionItem item = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 10, -100, 0, null, null, null, null, null);
        List<String> errors = validator.validateItem(QuestionType.DSA, item);
        assertThat(errors).contains("timeLimitMs must be greater than 0", "memoryLimitKb must be greater than 0");
    }

    @Test
    @DisplayName("SQL schemaSql validation: required and must contain CREATE TABLE (case-insensitive)")
    void sqlSchemaSqlValidation() {
        ImportQuestionItem missingSchema = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 10, null, null, "", false, null, null, null);
        assertThat(validator.validateItem(QuestionType.SQL, missingSchema)).contains("schemaSql is required");

        ImportQuestionItem noCreateTable = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 10, null, null, "INSERT INTO t VALUES (1);", false, null, null, null);
        assertThat(validator.validateItem(QuestionType.SQL, noCreateTable))
                .contains("schemaSql must contain at least one CREATE TABLE statement");

        ImportQuestionItem caseInsensitive = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 10, null, null, "create table test (id int);", false, null, null, null);
        assertThat(validator.validateItem(QuestionType.SQL, caseInsensitive)).isEmpty();

        ImportQuestionItem tempTable = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 10, null, null, "CREATE TEMP TABLE test (id int);", false, null, null, null);
        assertThat(validator.validateItem(QuestionType.SQL, tempTable)).isEmpty();
    }

    @Test
    @DisplayName("MCQ options validation: minimum 2, no blank options")
    void mcqOptionsValidation() {
        ImportQuestionItem oneOpt = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 5, null, null, null, null, List.of("Option 1"), List.of(1), null);
        assertThat(validator.validateItem(QuestionType.MCQ, oneOpt)).contains("at least 2 options are required");

        ImportQuestionItem blankOpt = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 5, null, null, null, null, List.of("Option 1", "   "), List.of(1), null);
        assertThat(validator.validateItem(QuestionType.MCQ, blankOpt)).contains("option 2 is blank");
    }

    @Test
    @DisplayName("MCQ correct validation: required, 1-based bounds [0], [5] for 4 options, and [1,1] duplicates fail")
    void mcqCorrectValidation() {
        List<String> fourOpts = List.of("A", "B", "C", "D");

        ImportQuestionItem emptyCorrect = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 5, null, null, null, null, fourOpts, List.of(), null);
        assertThat(validator.validateItem(QuestionType.MCQ, emptyCorrect))
                .contains("correct is required (1-based option numbers, e.g. [2])");

        ImportQuestionItem zeroIndex = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 5, null, null, null, null, fourOpts, List.of(0), null);
        assertThat(validator.validateItem(QuestionType.MCQ, zeroIndex))
                .contains("correct contains 0 but there are only 4 options");

        ImportQuestionItem outOfBounds = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 5, null, null, null, null, fourOpts, List.of(5), null);
        assertThat(validator.validateItem(QuestionType.MCQ, outOfBounds))
                .contains("correct contains 5 but there are only 4 options");

        ImportQuestionItem duplicates = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 5, null, null, null, null, fourOpts, List.of(1, 1), null);
        assertThat(validator.validateItem(QuestionType.MCQ, duplicates))
                .contains("correct contains duplicate numbers");

        ImportQuestionItem validMultiple = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 5, null, null, null, null, fourOpts, List.of(1, 3), null);
        assertThat(validator.validateItem(QuestionType.MCQ, validMultiple)).isEmpty();
    }

    @Test
    @DisplayName("THEORY question has optional modelAnswer")
    void theoryQuestionOptionalModelAnswer() {
        ImportQuestionItem withModel = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 10, null, null, null, null, null, null, "Sample answer");
        assertThat(validator.validateItem(QuestionType.THEORY, withModel)).isEmpty();

        ImportQuestionItem withoutModel = new ImportQuestionItem(
                "Q1", "Desc", "EASY", 10, null, null, null, null, null, null, null);
        assertThat(validator.validateItem(QuestionType.THEORY, withoutModel)).isEmpty();
    }
}
