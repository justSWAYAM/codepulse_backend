package com.codepulse_backend.submission.event;

import java.util.UUID;

/**
 * Published once every SUBMIT of a finalized session has a final status.
 * Module 9 aggregates results on this.
 */
public record SessionScoringCompletedEvent(
        UUID sessionId,
        UUID contestId,
        UUID candidateId
) {}
