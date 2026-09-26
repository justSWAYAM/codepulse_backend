package com.codepulse_backend.testcase.dto;

// Parsed shape of one CSV row, before validation and creation.
// CSV columns: input, expected_output, is_sample, weight
public record TestCaseCsvRow(
        String input,
        String expectedOutput,
        boolean isSample,
        int weight
) {}