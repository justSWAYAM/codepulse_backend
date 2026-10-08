package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.enums.QuestionType;

import java.util.ArrayList;
import java.util.List;

public final class ImportFieldSpec {

    private static final List<String> COMMON = List.of("title", "description", "difficulty", "points");

    private ImportFieldSpec() {}

    public static List<String> fieldsFor(QuestionType type) {
        List<String> fields = new ArrayList<>(COMMON);
        switch (type) {
            case DSA    -> fields.addAll(List.of("timeLimitMs", "memoryLimitKb"));
            case SQL    -> fields.addAll(List.of("schemaSql", "orderMatters"));
            case MCQ    -> fields.addAll(List.of("options", "correct"));
            case THEORY -> fields.add("modelAnswer");
        }
        return List.copyOf(fields);
    }
}
