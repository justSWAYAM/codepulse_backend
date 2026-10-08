package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.library.imports.dto.ImportQuestionItem;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class QuestionImportValidator {

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)\\bCREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TABLE\\b");

    public List<String> validateItem(QuestionType type, ImportQuestionItem it) {
        List<String> e = new ArrayList<>();

        if (isBlank(it.title()))        e.add("title is required");
        if (isBlank(it.description()))  e.add("description is required");

        if (isBlank(it.difficulty())) {
            e.add("difficulty is required (EASY, MEDIUM or HARD)");
        } else if (!Set.of("EASY", "MEDIUM", "HARD").contains(normalizeDifficulty(it.difficulty()))) {
            e.add("difficulty must be EASY, MEDIUM or HARD (got '" + it.difficulty().trim() + "')");
        }

        if (it.points() == null)  e.add("points is required");
        else if (it.points() <= 0) e.add("points must be greater than 0");

        switch (type) {
            case DSA -> {
                if (it.timeLimitMs() != null && it.timeLimitMs() <= 0)       e.add("timeLimitMs must be greater than 0");
                if (it.memoryLimitKb() != null && it.memoryLimitKb() <= 0)   e.add("memoryLimitKb must be greater than 0");
            }
            case SQL -> {
                if (isBlank(it.schemaSql())) {
                    e.add("schemaSql is required");
                } else if (!CREATE_TABLE.matcher(it.schemaSql()).find()) {
                    e.add("schemaSql must contain at least one CREATE TABLE statement");
                }
            }
            case MCQ -> validateMcq(it, e);
            case THEORY -> { /* modelAnswer optional */ }
        }
        return e;
    }

    private void validateMcq(ImportQuestionItem it, List<String> e) {
        List<String> options = it.options();
        if (options == null || options.size() < 2) {
            e.add("at least 2 options are required");
        } else {
            for (int i = 0; i < options.size(); i++) {
                if (isBlank(options.get(i))) e.add("option " + (i + 1) + " is blank");
            }
        }

        List<Integer> correct = it.correct();
        if (correct == null || correct.isEmpty()) {
            e.add("correct is required (1-based option numbers, e.g. [2])");
        } else {
            int optionCount = options == null ? 0 : options.size();
            for (Integer n : correct) {
                if (n == null || n < 1 || n > optionCount) {
                    e.add("correct contains " + n + " but there are only " + optionCount + " options");
                }
            }
            if (new HashSet<>(correct).size() != correct.size()) {
                e.add("correct contains duplicate numbers");
            }
        }
    }

    public static String normalizeDifficulty(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
