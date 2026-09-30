package com.codepulse_backend.execution.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Judge0SubmissionRequest(
        @JsonProperty("source_code")
        String sourceCode,

        @JsonProperty("language_id")
        int languageId,

        String stdin,

        @JsonProperty("cpu_time_limit")
        double cpuTimeLimit,

        @JsonProperty("memory_limit")
        long memoryLimit, // Added the missing comma here

        @JsonProperty("expected_output")
        String expectedOutput // Added the field type and name here
) {}