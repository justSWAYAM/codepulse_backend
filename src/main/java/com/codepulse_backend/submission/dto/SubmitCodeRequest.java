package com.codepulse_backend.submission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SubmitCodeRequest(
        @NotNull UUID questionId,
        @NotBlank String language,
        @NotBlank @Size(max = 65_536) String sourceCode
) {
}