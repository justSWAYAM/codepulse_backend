package com.codepulse_backend.contest.dto;

import java.util.List;
import java.util.UUID;

public record AssignCandidatesResult(
        int assignedCount,
        int alreadyAssignedCount,
        int notFoundCount,
        List<UUID> failedIds
) {}
