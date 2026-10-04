package com.codepulse_backend.execution;

import com.codepulse_backend.common.enums.TestCaseResultStatus;
import com.codepulse_backend.execution.dto.ExecutionRequest;
import com.codepulse_backend.execution.dto.ExecutionResult;
import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import com.codepulse_backend.execution.event.SubmissionEvaluatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExecutionWorkerTest {

    @Test
    void shouldProcessQueuedJobAndPublishEvent() {

        SubmissionQueueService queueService =
                mock(SubmissionQueueService.class);

        CodeExecutionService executionService =
                mock(CodeExecutionService.class);

        ApplicationEventPublisher eventPublisher =
                mock(ApplicationEventPublisher.class);

        UUID submissionId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();

        QueuedSubmissionJob job =
                new QueuedSubmissionJob(
                        submissionId,
                        questionId,
                        sessionId,
                        "print(\"hello\")",
                        "PYTHON",
                        List.of(
                                new QueuedSubmissionJob.TestCasePayload(
                                        testCaseId,
                                        "",
                                        "expected output",
                                        5000,
                                        256000
                                )
                        )
                );

        when(queueService.pop())
                .thenReturn(job)
                .thenReturn(null);

        when(executionService.execute(any(ExecutionRequest.class)))
                .thenReturn(
                        new ExecutionResult(
                                TestCaseResultStatus.PASSED,
                                "hello\n",
                                null,
                                null,
                                68L,
                                9336L
                        )
                );

        ExecutionWorker worker =
                new ExecutionWorker(
                        queueService,
                        executionService,
                        eventPublisher,
                        anySubmission -> true,
                        Runnable::run, // synchronous, so assertions see the finished job
                        4
                );

        worker.processQueue();

        verify(queueService, times(2)).pop();

        verify(executionService)
                .execute(any(ExecutionRequest.class));

        verify(eventPublisher).publishEvent(
                any(SubmissionEvaluatedEvent.class)
        );
    }

    @Test
    void shouldDoNothingWhenQueueIsEmpty() {

        SubmissionQueueService queueService =
                mock(SubmissionQueueService.class);

        CodeExecutionService executionService =
                mock(CodeExecutionService.class);

        ApplicationEventPublisher eventPublisher =
                mock(ApplicationEventPublisher.class);

        when(queueService.pop()).thenReturn(null);

        ExecutionWorker worker =
                new ExecutionWorker(
                        queueService,
                        executionService,
                        eventPublisher,
                        anySubmission -> true,
                        Runnable::run, // synchronous, so assertions see the finished job
                        4
                );

        worker.processQueue();

        verify(queueService).pop();

        verifyNoInteractions(executionService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void shouldPublishAllTestCaseOutcomes() {

        SubmissionQueueService queueService =
                mock(SubmissionQueueService.class);

        CodeExecutionService executionService =
                mock(CodeExecutionService.class);

        ApplicationEventPublisher eventPublisher =
                mock(ApplicationEventPublisher.class);

        UUID submissionId = UUID.randomUUID();

        UUID testCaseId1 = UUID.randomUUID();
        UUID testCaseId2 = UUID.randomUUID();

        QueuedSubmissionJob job =
                new QueuedSubmissionJob(
                        submissionId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "print(\"hello\")",
                        "PYTHON",
                        List.of(
                                new QueuedSubmissionJob.TestCasePayload(
                                        testCaseId1,
                                        "input-1",
                                        "expected output 1",
                                        5000,
                                        256000
                                ),
                                new QueuedSubmissionJob.TestCasePayload(
                                        testCaseId2,
                                        "input-2",
                                        "expected output 2",
                                        5000,
                                        256000
                                )
                        )
                );

        when(queueService.pop())
                .thenReturn(job)
                .thenReturn(null);

        when(executionService.execute(any(ExecutionRequest.class)))
                .thenReturn(
                        new ExecutionResult(
                                TestCaseResultStatus.PASSED,
                                "hello\n",
                                null,
                                null,
                                50L,
                                9000L
                        )
                );

        ExecutionWorker worker =
                new ExecutionWorker(
                        queueService,
                        executionService,
                        eventPublisher,
                        anySubmission -> true,
                        Runnable::run, // synchronous, so assertions see the finished job
                        4
                );

        worker.processQueue();

        var captor =
                org.mockito.ArgumentCaptor.forClass(
                        SubmissionEvaluatedEvent.class
                );

        verify(eventPublisher).publishEvent(captor.capture());

        SubmissionEvaluatedEvent event =
                captor.getValue();

        assertEquals(submissionId, event.submissionId());
        assertEquals(2, event.outcomes().size());

        assertEquals(
                testCaseId1,
                event.outcomes().get(0).testCaseId()
        );

        assertEquals(
                testCaseId2,
                event.outcomes().get(1).testCaseId()
        );

        assertEquals(
                TestCaseResultStatus.PASSED,
                event.outcomes().get(0).status()
        );
    }

    @Test
    void neverRunsMoreJobsAtOnceThanTheConcurrencyLimit() throws Exception {

        SubmissionQueueService queueService = mock(SubmissionQueueService.class);
        CodeExecutionService executionService = mock(CodeExecutionService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        // Six jobs waiting; each execution blocks until the test lets it finish
        java.util.Deque<QueuedSubmissionJob> queue = new java.util.ArrayDeque<>();
        for (int i = 0; i < 6; i++) {
            queue.add(new QueuedSubmissionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "print(1)", "PYTHON",
                    List.of(new QueuedSubmissionJob.TestCasePayload(UUID.randomUUID(), "", "1", 1000, 256000))));
        }
        when(queueService.pop()).thenAnswer(inv -> queue.poll());
        when(executionService.execute(any(ExecutionRequest.class))).thenAnswer(inv -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            running.decrementAndGet();
            return new ExecutionResult(TestCaseResultStatus.PASSED, "1", null, null, 1L, 100L);
        });

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            ExecutionWorker worker = new ExecutionWorker(
                    queueService, executionService, eventPublisher, anySubmission -> true, pool, 2);

            worker.processQueue();
            Thread.sleep(300);

            // Two slots: two jobs running, the other four still waiting in the queue
            assertEquals(2, running.get());
            assertEquals(4, queue.size());

            release.countDown();
            for (int i = 0; i < 20 && !queue.isEmpty(); i++) {
                Thread.sleep(100);
                worker.processQueue();
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));

            assertEquals(2, peak.get());
            assertTrue(queue.isEmpty());
            verify(eventPublisher, times(6)).publishEvent(any(SubmissionEvaluatedEvent.class));
        } finally {
            pool.shutdownNow();
        }
    }
}
