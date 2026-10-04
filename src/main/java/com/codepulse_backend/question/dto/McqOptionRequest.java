package com.codepulse_backend.question.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A single MCQ option within a {@link CreateLibraryQuestionRequest}.
 * Options are ordered by their position in the list.
 */
public record McqOptionRequest(

        @NotBlank(message = "Option text is required")
        String text,

        boolean isCorrect

) {}
