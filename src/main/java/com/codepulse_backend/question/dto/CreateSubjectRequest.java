package com.codepulse_backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/library/subjects.
 * The service validates name uniqueness case-insensitively before persisting.
 */
public record CreateSubjectRequest(

        @NotBlank(message = "Subject name is required")
        @Size(max = 100, message = "Subject name must not exceed 100 characters")
        String name

) {}
