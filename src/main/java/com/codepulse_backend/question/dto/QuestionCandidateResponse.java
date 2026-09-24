package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;

import java.util.UUID;

/**
 * Restricted question view for Candidate role.
 * Intentionally omits: createdBy, createdAt, updatedAt, updatedBy.
 *
 * Module 5 (Test Cases) will extend this with:
 *   List<TestCaseSampleResponse> sampleTestCases  (sample-only, no expectedOutput)
 *
 * SECURITY: Using a completely separate record type (not @JsonIgnore on a shared DTO)
 * eliminates any possibility of field leakage through refactoring accidents.
 */
public record QuestionCandidateResponse(
        UUID id,
        UUID contestId,
        String title,
        String description,
        Difficulty difficulty,
        int points,
        int timeLimitMs,
        int memoryLimitKb,
        int orderIndex
) {}
