package com.codepulse_backend.execution;

import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ExecutionWorkerLoadTest {

    @Autowired
    private SubmissionQueueService submissionQueueService;

    @Test
    void shouldProcessTenJobsWithThreeTestCasesEach()
            throws InterruptedException {

        for (int i = 0; i < 10; i++) {

            List<QueuedSubmissionJob.TestCasePayload> testCases =
                    new ArrayList<>();

            for (int j = 0; j < 3; j++) {
                testCases.add(
                        new QueuedSubmissionJob.TestCasePayload(
                                UUID.randomUUID(),
                                "",
                                5000,
                                256000
                        )
                );
            }

            QueuedSubmissionJob job =
                    new QueuedSubmissionJob(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "print(\"hello\")",
                            "PYTHON",
                            testCases
                    );

            submissionQueueService.push(job);
        }

        Thread.sleep(30000);

        assertEquals(
                0,
                submissionQueueService.size()
        );
    }
}