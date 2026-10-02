package com.codepulse_backend.execution.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Judge0StatusResponse(
        String stdout,
        String stderr,

        @JsonProperty("compile_output")
        String compileOutput,

        String time,
        Long memory,
        Judge0Status status
) {

    /** Decodes the Base64 text fields returned when base64_encoded=true. */
    public Judge0StatusResponse base64Decoded() {
        return new Judge0StatusResponse(
                decode(stdout), decode(stderr), decode(compileOutput), time, memory, status);
    }

    // Judge0 wraps Base64 at 60 columns, so use the MIME decoder. Invalid UTF-8
    // from the program (binary output) becomes U+FFFD instead of failing the run.
    private static String decode(String value) {
        return value == null
                ? null
                : new String(java.util.Base64.getMimeDecoder().decode(value),
                        java.nio.charset.StandardCharsets.UTF_8);
    }

    public record Judge0Status(
            int id,
            String description
    ) {}
}