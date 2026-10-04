package com.codepulse_backend.result.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * adjustedScore null = revert to the automatic score. The upper bound (the question's
 * points) and the 2-decimal rule are checked in the service.
 */
public record ManualEvaluationRequest(
        @DecimalMin("0.00") BigDecimal adjustedScore,
        @NotBlank @Size(max = 2000) String comments
) {}
