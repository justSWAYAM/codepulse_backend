package com.codepulse_backend.result.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Staff only. adjustedScore null = reverted to the automatic score. */
public record ManualEvaluationResponse(
        UUID id,
        UUID submissionId,
        UUID questionId,
        BigDecimal adjustedScore,
        String comments,
        UUID evaluatorId,
        String evaluatorName,
        Instant evaluatedAt
) {}
