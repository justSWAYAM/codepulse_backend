package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.exception.ErrorCode;
import com.codepulse_backend.library.imports.config.ImportProperties;
import com.codepulse_backend.library.imports.exception.ImportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionImportParserTest {

    private QuestionImportParser parser;
    private ImportProperties props;

    @BeforeEach
    void setUp() {
        props = new ImportProperties(1048576L, 200, 2000, 262144);
        parser = new QuestionImportParser(props);
    }

    @Test
    @DisplayName("bare JSON array parses 1 row")
    void bareJsonArray() {
        String json = "[{\"title\":\"Question A\",\"description\":\"Desc A\",\"points\":5}]";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rowNumber()).isEqualTo(1);
        assertThat(rows.get(0).parseError()).isNull();
        assertThat(rows.get(0).item().title()).isEqualTo("Question A");
        assertThat(rows.get(0).item().points()).isEqualTo(5);
    }

    @Test
    @DisplayName("fenced markdown block parses rows")
    void fencedMarkdownBlock() {
        String json = "```json\n[{\"title\":\"Q1\"},{\"title\":\"Q2\"}]\n```";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).item().title()).isEqualTo("Q1");
        assertThat(rows.get(1).item().title()).isEqualTo("Q2");
    }

    @Test
    @DisplayName("prose before and after fenced block parses rows")
    void proseSurroundingFencedBlock() {
        String text = "Here are your questions:\n```json\n[{\"title\":\"Q1\"}]\n```\nHope that helps!";
        List<ParsedRow> rows = parser.parse(text);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).item().title()).isEqualTo("Q1");
    }

    @Test
    @DisplayName("two fenced blocks, first is not an array -> second block used")
    void twoFencedBlocksFirstNotArray() {
        String text = "Here is some text:\n```text\nNot an array\n```\nAnd here is the JSON:\n```json\n[{\"title\":\"Q1\"}]\n```";
        List<ParsedRow> rows = parser.parse(text);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).item().title()).isEqualTo("Q1");
    }

    @Test
    @DisplayName("wrapped in {\"questions\":[...]} object is accepted")
    void wrapperObjectAccepted() {
        String json = "{\"questions\":[{\"title\":\"Q1\"}]}";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).item().title()).isEqualTo("Q1");
    }

    @Test
    @DisplayName("trailing comma in JSON array is tolerated")
    void trailingCommaTolerated() {
        String json = "[{\"title\":\"Q1\"},]";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).item().title()).isEqualTo("Q1");
    }

    @Test
    @DisplayName("raw unescaped newline inside a JSON string is tolerated")
    void rawNewlineInStringTolerated() {
        String json = "[{\"title\":\"Line1\nLine2\"}]";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).item().title()).isEqualTo("Line1\nLine2");
    }

    @Test
    @DisplayName("leading UTF-8 BOM is stripped")
    void leadingBomStripped() {
        String json = "\uFEFF[{\"title\":\"Q1\"}]";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).item().title()).isEqualTo("Q1");
    }

    @Test
    @DisplayName("single integer correct value parsed as list [2]")
    void singleValueAsArrayForCorrect() {
        String json = "[{\"title\":\"MCQ\",\"correct\":2}]";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).item().correct()).containsExactly(2);
    }

    @Test
    @DisplayName("string points coerced to integer")
    void pointsStringCoercedToInteger() {
        String json = "[{\"title\":\"Q1\",\"points\":\"5\"}]";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).item().points()).isEqualTo(5);
    }

    @Test
    @DisplayName("unknown extra field is ignored")
    void unknownExtraFieldIgnored() {
        String json = "[{\"title\":\"Q1\",\"extraField\":\"ignored\"}]";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).item().title()).isEqualTo("Q1");
    }

    @Test
    @DisplayName("non-object element in array yields row error, other rows succeed")
    void nonObjectElementYieldsRowError() {
        String json = "[{\"title\":\"Q1\"},42,{\"title\":\"Q3\"}]";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).parseError()).isNull();
        assertThat(rows.get(1).parseError()).isEqualTo("Row is not a JSON object");
        assertThat(rows.get(2).parseError()).isNull();
    }

    @Test
    @DisplayName("options of wrong type yields row error mentioning field options")
    void wrongFieldTypeYieldsFriendlyError() {
        String json = "[{\"title\":\"Q1\",\"options\":[{\"text\":\"x\"}]}]";
        List<ParsedRow> rows = parser.parse(json);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).parseError()).contains("Field 'options'");
    }

    @Test
    @DisplayName("blank or null payload throws IMPORT_PAYLOAD_INVALID")
    void blankPayloadThrows() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(ImportException.class)
                .matches(e -> ((ImportException) e).getErrorCode() == ErrorCode.IMPORT_PAYLOAD_INVALID);

        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(ImportException.class)
                .matches(e -> ((ImportException) e).getErrorCode() == ErrorCode.IMPORT_PAYLOAD_INVALID);
    }

    @Test
    @DisplayName("empty JSON array throws IMPORT_PAYLOAD_INVALID")
    void emptyArrayThrows() {
        assertThatThrownBy(() -> parser.parse("[]"))
                .isInstanceOf(ImportException.class)
                .matches(e -> ((ImportException) e).getErrorCode() == ErrorCode.IMPORT_PAYLOAD_INVALID)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("JSON object without questions array throws IMPORT_PAYLOAD_INVALID")
    void topLevelObjectWithoutQuestionsThrows() {
        assertThatThrownBy(() -> parser.parse("{\"a\":1}"))
                .isInstanceOf(ImportException.class)
                .matches(e -> ((ImportException) e).getErrorCode() == ErrorCode.IMPORT_PAYLOAD_INVALID)
                .hasMessageContaining("top-level JSON must be an array");
    }

    @Test
    @DisplayName("malformed JSON throws IMPORT_PAYLOAD_INVALID with line/column")
    void malformedJsonThrowsWithLineColumn() {
        assertThatThrownBy(() -> parser.parse("[{\"title\":"))
                .isInstanceOf(ImportException.class)
                .matches(e -> ((ImportException) e).getErrorCode() == ErrorCode.IMPORT_PAYLOAD_INVALID)
                .hasMessageContaining("line")
                .hasMessageContaining("column");
    }

    @Test
    @DisplayName("more than 200 items throws IMPORT_LIMIT_EXCEEDED")
    void overLimitThrows() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"title\":\"Q").append(i).append("\"}");
        }
        sb.append("]");

        assertThatThrownBy(() -> parser.parse(sb.toString()))
                .isInstanceOf(ImportException.class)
                .matches(e -> ((ImportException) e).getErrorCode() == ErrorCode.IMPORT_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("payload larger than max bytes (measured in UTF-8 bytes) throws IMPORT_PAYLOAD_TOO_LARGE")
    void overMaxBytesThrows() {
        // Create a custom small parser to test byte limit
        ImportProperties smallProps = new ImportProperties(50L, 200, 2000, 262144);
        QuestionImportParser smallParser = new QuestionImportParser(smallProps);

        // Multi-byte characters: each "€" is 3 UTF-8 bytes
        String text = "[{\"title\":\"€€€€€€€€€€€€€€€€€\"}]";
        assertThat(text.length()).isLessThan(50);
        assertThat(text.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(50);

        assertThatThrownBy(() -> smallParser.parse(text))
                .isInstanceOf(ImportException.class)
                .matches(e -> ((ImportException) e).getErrorCode() == ErrorCode.IMPORT_PAYLOAD_TOO_LARGE);
    }
}
