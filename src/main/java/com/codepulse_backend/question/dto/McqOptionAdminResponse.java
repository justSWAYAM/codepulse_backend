package com.codepulse_backend.question.dto;

import java.util.UUID;

/**
 * MCQ option view for Admin and Evaluator roles.
 * SECURITY: includes {@code isCorrect} — must NEVER be returned to a CANDIDATE.
 */
public record McqOptionAdminResponse(
        UUID id,
        String text,
        boolean isCorrect,
        int orderIndex
) {}
