package com.codepulse_backend.result.event;

import com.codepulse_backend.contest.event.ContestCompletedEvent;
import com.codepulse_backend.result.service.ResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Creates ABSENT results for candidates who never started. Order relative to
 * ContestCompletedSessionListener doesn't matter: each finalized session fires its own
 * scoring event, and every recompute is idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContestCompletedResultListener {

    private final ResultService resultService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContestCompleted(ContestCompletedEvent event) {
        try {
            resultService.createAbsentResults(event.contestId());
        } catch (Exception e) {
            log.error("Failed to create absent results for contest {}", event.contestId(), e);
        }
    }
}
