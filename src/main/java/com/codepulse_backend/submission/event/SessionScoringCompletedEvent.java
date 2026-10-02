package com.codepulse_backend.submission.event;

import java.util.UUID;

/**
 * Published once every SUBMIT of a finalized session has a final status.
 * Module 9 aggregates results on this.
 * <p>
 * It is published after the deciding transaction has committed, sometimes with no
 * transaction active (worker thread, recovery scheduler). Listeners should therefore
 * use a plain {@code @EventListener} with {@code @Transactional(propagation = REQUIRES_NEW)},
 * or {@code @TransactionalEventListener(fallbackExecution = true)}.
 */
public record SessionScoringCompletedEvent(
        UUID sessionId,
        UUID contestId,
        UUID candidateId
) {}
