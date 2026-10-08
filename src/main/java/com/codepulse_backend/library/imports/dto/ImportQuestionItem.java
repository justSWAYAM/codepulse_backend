package com.codepulse_backend.library.imports.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportQuestionItem(
        String title,
        String description,
        String difficulty,          // raw text; normalised/validated later (friendlier errors than an enum parse failure)
        Integer points,
        Integer timeLimitMs,        // DSA
        Integer memoryLimitKb,      // DSA
        String schemaSql,           // SQL
        Boolean orderMatters,       // SQL
        List<String> options,       // MCQ
        List<Integer> correct,      // MCQ, 1-based option numbers
        String modelAnswer) {       // THEORY
}
