package com.codepulse_backend.contest.dto;

public record UnassignCandidatesResult(
        int removedCount,
        int notAssignedCount
) {}
