package com.codepulse_backend.submission.scheduler;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.config.SubmissionProperties;
import com.codepulse_backend.execution.SubmissionQueueService;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import com.codepulse_backend.submission.service.SubmissionPersistenceService;
import com.codepulse_backend.submission.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Finds SUBMIT rows stuck in PENDING (job lost from Redis, worker crash, listener failure).
 * Re-queues them until max-queue-attempts, then marks them SYSTEM_ERROR so they can be rejudged.
 * Re-processing a job that was merely slow is harmless: applyEvaluation is idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubmissionRecoveryScheduler {

    private final SubmissionRepository submissionRepository;
    private final SubmissionPersistenceService persistenceService;
    private final SubmissionService submissionService;
    private final SubmissionQueueService queueService;
    private final SubmissionProperties properties;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Scheduled(
            fixedDelayString = "${submission.recovery.interval-ms:60000}",
            initialDelayString = "${submission.recovery.interval-ms:60000}"
    )
    public void recoverStalePending() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(Duration.ofMinutes(properties.getRecovery().getStaleAfterMinutes()));
        int maxAttempts = properties.getRecovery().getMaxQueueAttempts();

        List<Submission> stale = submissionRepository.findStalePending(
                SubmissionStatus.PENDING, cutoff, maxAttempts);

        int requeued = 0;
        for (Submission submission : stale) {
            try {
                queueService.push(submissionService.buildJobFromStored(submission));
                if (persistenceService.markRequeued(submission.getId(), now)) {
                    requeued++;
                }
            } catch (Exception e) {
                // Leave it; the next tick retries. Never log source code.
                log.error("Recovery could not re-queue submission {}", submission.getId(), e);
            }
        }

        List<Submission> exhausted = submissionRepository
                .findByStatusAndQueueAttemptsGreaterThanEqualAndQueuedAtBefore(
                        SubmissionStatus.PENDING, maxAttempts, cutoff);

        int abandoned = 0;
        for (Submission submission : exhausted) {
            try {
                persistenceService.markAbandoned(submission.getId(), now)
                        .ifPresent(publisher::publishEvent);
                abandoned++;
            } catch (Exception e) {
                log.error("Recovery could not mark submission {} as SYSTEM_ERROR", submission.getId(), e);
            }
        }

        if (requeued > 0 || abandoned > 0) {
            log.warn("Submission recovery: re-queued={}, marked SYSTEM_ERROR={}", requeued, abandoned);
        }
    }
}
