package com.codepulse_backend.submission.scheduler;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.config.SubmissionProperties;
import com.codepulse_backend.execution.SubmissionQueueService;
import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.event.SessionScoringCompletedEvent;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import com.codepulse_backend.submission.service.SubmissionPersistenceService;
import com.codepulse_backend.submission.service.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionRecoverySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(10 * 60);

    @Mock private SubmissionRepository submissionRepository;
    @Mock private SubmissionPersistenceService persistenceService;
    @Mock private SubmissionService submissionService;
    @Mock private SubmissionQueueService queueService;
    @Mock private ApplicationEventPublisher publisher;

    private SubmissionRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SubmissionRecoveryScheduler(
                submissionRepository,
                persistenceService,
                submissionService,
                queueService,
                new SubmissionProperties(),
                publisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void stalePendingIsRequeuedAndAttemptCounted() {
        Submission stale = submission();
        QueuedSubmissionJob job = new QueuedSubmissionJob(
                stale.getId(), stale.getQuestionId(), stale.getSessionId(), "src", "JAVA", List.of());

        when(submissionRepository.findStalePending(SubmissionStatus.PENDING, CUTOFF, 3))
                .thenReturn(List.of(stale));
        when(submissionRepository.findByStatusAndQueueAttemptsGreaterThanEqualAndQueuedAtBefore(
                SubmissionStatus.PENDING, 3, CUTOFF)).thenReturn(List.of());
        when(submissionService.buildJobFromStored(stale)).thenReturn(job);
        when(persistenceService.markRequeued(stale.getId(), NOW)).thenReturn(true);

        scheduler.recoverStalePending();

        verify(queueService).push(job);
        verify(persistenceService).markRequeued(stale.getId(), NOW);
    }

    @Test
    void failedPushDoesNotCountAnAttempt() {
        Submission stale = submission();

        when(submissionRepository.findStalePending(SubmissionStatus.PENDING, CUTOFF, 3))
                .thenReturn(List.of(stale));
        when(submissionRepository.findByStatusAndQueueAttemptsGreaterThanEqualAndQueuedAtBefore(
                SubmissionStatus.PENDING, 3, CUTOFF)).thenReturn(List.of());
        when(submissionService.buildJobFromStored(stale)).thenReturn(
                new QueuedSubmissionJob(stale.getId(), null, null, "src", "JAVA", List.of()));
        doThrow(new RuntimeException("redis down")).when(queueService).push(any());

        scheduler.recoverStalePending();

        verify(persistenceService, never()).markRequeued(any(), any());
    }

    @Test
    void exhaustedPendingIsMarkedSystemErrorAndCompletionPublished() {
        Submission exhausted = submission();
        SessionScoringCompletedEvent done = new SessionScoringCompletedEvent(
                exhausted.getSessionId(), UUID.randomUUID(), exhausted.getCandidateId());

        when(submissionRepository.findStalePending(SubmissionStatus.PENDING, CUTOFF, 3))
                .thenReturn(List.of());
        when(submissionRepository.findByStatusAndQueueAttemptsGreaterThanEqualAndQueuedAtBefore(
                SubmissionStatus.PENDING, 3, CUTOFF)).thenReturn(List.of(exhausted));
        when(persistenceService.markAbandoned(exhausted.getId(), NOW)).thenReturn(Optional.of(done));

        scheduler.recoverStalePending();

        verify(queueService, never()).push(any());
        verify(publisher).publishEvent(done);
    }

    @Test
    void backlogInQueueSkipsRecovery() {
        when(queueService.size()).thenReturn(4L);

        scheduler.recoverStalePending();

        verify(submissionRepository, never()).findStalePending(any(), any(), anyInt());
    }

    private Submission submission() {
        Submission s = new Submission();
        s.setId(UUID.randomUUID());
        s.setSessionId(UUID.randomUUID());
        s.setQuestionId(UUID.randomUUID());
        s.setCandidateId(UUID.randomUUID());
        s.setStatus(SubmissionStatus.PENDING);
        return s;
    }
}
