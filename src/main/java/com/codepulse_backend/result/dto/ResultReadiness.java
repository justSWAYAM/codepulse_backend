package com.codepulse_backend.result.dto;

/** Computed on every read, never stored (plan 2.9). */
public record ResultReadiness(
        int totalCandidates,
        int inProgress,
        int judging,
        int missing,
        int scored,
        int needsReview,
        int absent,
        boolean contestCompleted,
        boolean published,
        boolean readyToPublish
) {}
