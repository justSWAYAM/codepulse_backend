package com.codepulse_backend.execution;

import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SubmissionQueueServiceTest {

    @Autowired
    private SubmissionQueueService submissionQueueService;

    @Test
    void shouldPushAndPopJob() {

        QueuedSubmissionJob job =
                new QueuedSubmissionJob(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "print(\"hello\")",
                        "PYTHON",
                        List.of(
                                new QueuedSubmissionJob.TestCasePayload(
                                        UUID.randomUUID(),
                                        "input-1",
                                        5000,
                                        256000
                                ),
                                new QueuedSubmissionJob.TestCasePayload(
                                        UUID.randomUUID(),
                                        "input-2",
                                        5000,
                                        256000
                                )
                        )
                );

        long initialSize = submissionQueueService.size();

        submissionQueueService.push(job);

        assertEquals(
                initialSize + 1,
                submissionQueueService.size()
        );

        Object popped = submissionQueueService.pop();

        assertNotNull(popped);
        assertInstanceOf(QueuedSubmissionJob.class, popped);

        QueuedSubmissionJob result =
                (QueuedSubmissionJob) popped;

        assertEquals(job.submissionId(), result.submissionId());
        assertEquals(job.questionId(), result.questionId());
        assertEquals(job.sessionId(), result.sessionId());
        assertEquals(job.sourceCode(), result.sourceCode());
        assertEquals(job.languageName(), result.languageName());
        assertEquals(job.testCases(), result.testCases());
    }
}