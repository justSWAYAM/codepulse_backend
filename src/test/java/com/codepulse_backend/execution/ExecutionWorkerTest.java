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
                        eventPublisher
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
                        eventPublisher
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
                                        5000,
                                        256000
                                ),
                                new QueuedSubmissionJob.TestCasePayload(
                                        testCaseId2,
                                        "input-2",
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
                        eventPublisher
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
}