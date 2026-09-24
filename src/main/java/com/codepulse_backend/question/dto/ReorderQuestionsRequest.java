package com.codepulse_backend.question.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record ReorderQuestionsRequest(

        @NotEmpty(message = "Question IDs list must not be empty")
        List<UUID> orderedIds  // index 0 → orderIndex 1, index 1 → orderIndex 2, etc.
) {}
