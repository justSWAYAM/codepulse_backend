package com.codepulse_backend.library.imports;

import com.codepulse_backend.library.imports.config.ImportProperties;
import com.codepulse_backend.library.imports.dto.ImportQuestionItem;
import com.codepulse_backend.library.imports.exception.ImportException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class QuestionImportParser {

    private static final Pattern FENCE =
            Pattern.compile("(?s)```[A-Za-z0-9_+-]*[ \\t]*\\R?(.*?)```");

    // Private on purpose — NOT a Spring bean (see D9 / pitfall P1).
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)   // LLMs put raw newlines inside strings
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)   // "correct": 2  → [2]
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ImportProperties props;

    public QuestionImportParser(ImportProperties props) {
        this.props = props;
    }

    public List<ParsedRow> parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw ImportException.invalid("Nothing was pasted.");
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > props.maxPayloadBytes()) {
            throw ImportException.tooLarge(props.maxPayloadBytes());
        }

        ArrayNode array = findQuestionArray(payload);

        if (array.isEmpty()) {
            throw ImportException.invalid("The JSON array is empty — no questions found.");
        }
        if (array.size() > props.maxQuestions()) {
            throw ImportException.limit(array.size(), props.maxQuestions());
        }

        List<ParsedRow> rows = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            int rowNumber = i + 1;
            JsonNode node = array.get(i);
            if (!node.isObject()) {
                rows.add(ParsedRow.bad(rowNumber, "Row is not a JSON object"));
                continue;
            }
            try {
                rows.add(ParsedRow.ok(rowNumber, mapper.treeToValue(node, ImportQuestionItem.class)));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                rows.add(ParsedRow.bad(rowNumber, friendly(e)));
            }
        }
        return rows;
    }

    /** Tries: whole text → each fenced block → first '[' to last ']'. First one that yields an array wins. */
    private ArrayNode findQuestionArray(String payload) {
        String text = payload.startsWith("\uFEFF") ? payload.substring(1) : payload;   // strip BOM
        List<String> candidates = new ArrayList<>();
        candidates.add(text.trim());

        Matcher m = FENCE.matcher(text);
        while (m.find()) {
            candidates.add(m.group(1).trim());
        }

        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            candidates.add(text.substring(start, end + 1));
        }

        String lastProblem = "No JSON array found. Paste the exact output of the AI.";
        for (String candidate : candidates) {
            if (candidate.isEmpty()) continue;
            try {
                JsonNode root = mapper.readTree(candidate);
                if (root.isArray()) return (ArrayNode) root;
                if (root.isObject() && root.path("questions").isArray()) {
                    return (ArrayNode) root.get("questions");        // tolerated wrapper: {"questions":[...]}
                }
                lastProblem = "The top-level JSON must be an array of questions.";
            } catch (JsonProcessingException e) {
                lastProblem = "The pasted text is not valid JSON: " + e.getOriginalMessage()
                        + " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            }
        }
        throw ImportException.invalid(lastProblem);
    }

    private String friendly(Exception e) {
        if (e instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            String field = jme.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            if (!field.isEmpty()) return "Field '" + field + "' has an invalid value";
        }
        return "Row has an invalid format";
    }
}
