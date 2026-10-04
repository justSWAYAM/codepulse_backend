package com.codepulse_backend.question.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Request body for POST /api/contests/{contestId}/questions/from-library.
 *
 * Sends a list of library question IDs to deep-copy into the contest.
 * Already-copied questions are silently skipped (idempotent).
 */
public record AddFromLibraryRequest(

        @NotEmpty(message = "At least one question ID must be provided")
        List<UUID> questionIds

) {}
