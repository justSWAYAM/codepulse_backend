package com.codepulse_backend.execution;

import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ExecutionWorkerIntegrationTest {

    @Autowired
    private SubmissionQueueService submissionQueueService;

    @Test
    void shouldQueueSubmissionForWorkerProcessing() throws InterruptedException {

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
                                        "",
                                        "expected output",
                                        5000,
                                        256000
                                )
                        )
                );

        submissionQueueService.push(job);

        Thread.sleep(3000);

        assertEquals(
                0,
                submissionQueueService.size()
        );
    }
}