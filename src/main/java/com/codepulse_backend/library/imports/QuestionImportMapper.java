package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.library.imports.config.ImportProperties;
import com.codepulse_backend.library.imports.dto.ImportQuestionItem;
import com.codepulse_backend.question.dto.CreateLibraryQuestionRequest;
import com.codepulse_backend.question.dto.McqOptionRequest;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class QuestionImportMapper {

    private final ImportProperties props;

    public QuestionImportMapper(ImportProperties props) {
        this.props = props;
    }

    public CreateLibraryQuestionRequest toRequest(QuestionType type, UUID subjectId, ImportQuestionItem it) {
        String title = it.title() != null ? it.title().trim() : null;
        String description = it.description() != null ? it.description().strip() : null;
        Difficulty difficulty = it.difficulty() != null
                ? Difficulty.valueOf(QuestionImportValidator.normalizeDifficulty(it.difficulty()))
                : null;
        int points = it.points() != null ? it.points() : 0;

        Integer timeLimitMs = null;
        Integer memoryLimitKb = null;
        String schemaSql = null;
        Boolean orderMatters = null;
        String modelAnswer = null;
        List<McqOptionRequest> options = null;

        switch (type) {
            case DSA -> {
                timeLimitMs = it.timeLimitMs() != null ? it.timeLimitMs() : props.defaultTimeLimitMs();
                memoryLimitKb = it.memoryLimitKb() != null ? it.memoryLimitKb() : props.defaultMemoryLimitKb();
            }
            case SQL -> {
                timeLimitMs = it.timeLimitMs() != null ? it.timeLimitMs() : props.defaultTimeLimitMs();
                memoryLimitKb = it.memoryLimitKb() != null ? it.memoryLimitKb() : props.defaultMemoryLimitKb();
                schemaSql = it.schemaSql();
                orderMatters = Boolean.TRUE.equals(it.orderMatters());
            }
            case MCQ -> {
                options = toOptions(it);
            }
            case THEORY -> {
                modelAnswer = blankToNull(it.modelAnswer());
            }
        }

        return new CreateLibraryQuestionRequest(
                subjectId,
                type,
                title,
                description,
                difficulty,
                points,
                timeLimitMs,
                memoryLimitKb,
                schemaSql,
                orderMatters,
                modelAnswer,
                options
        );
    }

    /** options[i] <-> correct contains (i+1). Order and text preserved exactly (trimmed only). */
    private List<McqOptionRequest> toOptions(ImportQuestionItem it) {
        if (it.options() == null) return List.of();
        Set<Integer> correct = it.correct() == null ? Set.of() : new HashSet<>(it.correct());
        List<McqOptionRequest> out = new ArrayList<>();
        for (int i = 0; i < it.options().size(); i++) {
            out.add(new McqOptionRequest(
                    it.options().get(i).trim(),
                    correct.contains(i + 1)
            ));
        }
        return out;
    }

    // ---- preview helpers (display only) ----

    public String detail(QuestionType type, ImportQuestionItem it) {
        return switch (type) {
            case DSA -> "time " + (it.timeLimitMs() != null ? it.timeLimitMs() : props.defaultTimeLimitMs()) + " ms · memory "
                    + (it.memoryLimitKb() != null ? it.memoryLimitKb() : props.defaultMemoryLimitKb()) + " KB";
            case SQL -> "schema: " + countStatements(it.schemaSql()) + " statements · orderMatters=" + Boolean.TRUE.equals(it.orderMatters());
            case MCQ -> (it.options() == null ? 0 : it.options().size()) + " options · correct: "
                    + (it.correct() == null ? "-" : it.correct().stream().map(String::valueOf).collect(Collectors.joining(",")));
            case THEORY -> "model answer: " + (blankToNull(it.modelAnswer()) != null ? "yes" : "no");
        };
    }

    public String descriptionPreview(String description) {
        if (description == null) return "";
        String flat = description.strip().replaceAll("\\s+", " ");
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private static int countStatements(String sql) {
        return sql == null ? 0 : (int) Arrays.stream(sql.split(";")).filter(s -> !s.isBlank()).count();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
