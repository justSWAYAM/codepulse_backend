package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.QuestionType;

import java.time.Instant;
import java.util.UUID;

/**
 * Flattened view of a library question for browsing purposes.
 * Used by GET /api/library/questions (paged list).
 *
 * SECURITY: does NOT include model_answer, is_correct or any hidden-test-case data.
 * authorName is the creator's full name — not their ID or email — to limit PII exposure.
 */
public record LibraryQuestionResponse(
        UUID id,
        String title,
        QuestionType questionType,
        Difficulty difficulty,
        int points,
        UUID subjectId,
        String subjectName,
        /** Full name of the user who authored this library question. */
        String authorName,
        Instant createdAt,
        /** True when this DSA/SQL library question has no test cases yet. */
        boolean hasNoTestCases,
        UUID sourceQuestionId
) {}
