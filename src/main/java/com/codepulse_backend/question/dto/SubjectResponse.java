package com.codepulse_backend.question.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload for a subject folder.
 * Returned by GET /api/library/subjects and POST /api/library/subjects.
 */
public record SubjectResponse(
        UUID id,
        String name,
        UUID createdBy,
        Instant createdAt
) {}
