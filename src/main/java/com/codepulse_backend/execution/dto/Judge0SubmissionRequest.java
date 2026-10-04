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

        @JsonProperty("wall_time_limit")
        double wallTimeLimit,

        @JsonProperty("memory_limit")
        long memoryLimit,

        @JsonProperty("expected_output")
        String expectedOutput
) {

    /** Same request with every text field Base64-encoded, for base64_encoded=true. */
    public Judge0SubmissionRequest base64Encoded() {
        return new Judge0SubmissionRequest(
                encode(sourceCode), languageId, encode(stdin),
                cpuTimeLimit, wallTimeLimit, memoryLimit, encode(expectedOutput));
    }

    private static String encode(String value) {
        return value == null
                ? null
                : java.util.Base64.getEncoder().encodeToString(
                        value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}