package com.codepulse_backend.contest.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record AssignCandidatesRequest(
        @NotEmpty(message = "At least one candidate ID must be provided")
        List<@NotNull UUID> candidateIds
) {}
