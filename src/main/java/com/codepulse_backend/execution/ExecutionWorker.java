package com.codepulse_backend.execution;

import com.codepulse_backend.execution.dto.ExecutionRequest;
import com.codepulse_backend.execution.dto.ExecutionResult;
import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import com.codepulse_backend.execution.event.SubmissionEvaluatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ExecutionWorker {

    private static final int MAX_JOBS_PER_TICK = 5;

    private final SubmissionQueueService submissionQueueService;
    private final CodeExecutionService codeExecutionService;
    private final ApplicationEventPublisher eventPublisher;

    public ExecutionWorker(
            SubmissionQueueService submissionQueueService,
            CodeExecutionService codeExecutionService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.submissionQueueService = submissionQueueService;
        this.codeExecutionService = codeExecutionService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelay = 1000)
    public void processQueue() {

        for (int i = 0; i < MAX_JOBS_PER_TICK; i++) {

            Object queuedJob = submissionQueueService.pop();

            if (queuedJob == null) {
                return;
            }

            QueuedSubmissionJob job =
                    (QueuedSubmissionJob) queuedJob;

            List<SubmissionEvaluatedEvent.TestCaseOutcome> outcomes =
                    new ArrayList<>();

            for (QueuedSubmissionJob.TestCasePayload testCase :
                    job.testCases()) {

                ExecutionResult result =
                        codeExecutionService.execute(
                                new ExecutionRequest(
                                        job.submissionId(),
                                        testCase.testCaseId(),
                                        job.sourceCode(),
                                        job.languageName(),
                                        testCase.input(),
                                        testCase.expectedOutput(),
                                        testCase.timeLimitMs(),
                                        testCase.memoryLimitKb()
                                )
                        );

                outcomes.add(
                        new SubmissionEvaluatedEvent.TestCaseOutcome(
                                testCase.testCaseId(),
                                result.status(),
                                result.stdout(),
                                result.stderr(),
                                result.compileOutput(),
                                result.executionTimeMs() == null
                                        ? null
                                        : result.executionTimeMs().doubleValue(),
                                result.memoryUsedKb() == null
                                        ? null
                                        : result.memoryUsedKb().intValue()
                        )
                );
            }

            eventPublisher.publishEvent(
                    new SubmissionEvaluatedEvent(
                            job.submissionId(),
                            outcomes
                    )
            );
        }
    }
}