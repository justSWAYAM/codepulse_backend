package com.codepulse_backend.testcase.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateTestCaseRequest(
        @NotBlank(message = "Input is required")
        String input,

        @NotBlank(message = "Expected output is required")
        String expectedOutput,

        boolean isSample,

        @Min(value = 0, message = "Weight cannot be negative")
        int weight
) {}