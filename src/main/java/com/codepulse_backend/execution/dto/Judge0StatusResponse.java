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

    public record Judge0Status(
            int id,
            String description
    ) {}
}