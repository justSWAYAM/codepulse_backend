package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.testcase.dto.TestCaseAdminResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full question view for Admin and Evaluator roles.
 * Contains all metadata including createdBy and createdAt.
 *
 * Module 5 (Test Cases) will extend this with:
 *   List<TestCaseAdminResponse> testCases  (all test cases — sample + hidden)
 *
 * SECURITY: This record must NEVER be returned to a CANDIDATE. Use QuestionCandidateResponse instead.
 */
public record QuestionAdminResponse(
        UUID id,
        UUID contestId,
        String title,
        String description,
        Difficulty difficulty,
        int points,
        int timeLimitMs,
        int memoryLimitKb,
        int orderIndex,
        Instant createdAt,
        UUID createdBy,
        List<TestCaseAdminResponse> testCases
) {}
