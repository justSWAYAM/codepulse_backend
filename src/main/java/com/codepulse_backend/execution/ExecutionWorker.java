package com.codepulse_backend.execution;

import com.codepulse_backend.common.enums.TestCaseResultStatus;
import com.codepulse_backend.execution.dto.ExecutionRequest;
import com.codepulse_backend.execution.dto.ExecutionResult;
import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import com.codepulse_backend.execution.event.SubmissionEvaluatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ExecutionWorker {

    private static final int MAX_JOBS_PER_TICK = 5;

    private final SubmissionQueueService submissionQueueService;
    private final CodeExecutionService codeExecutionService;
    private final ApplicationEventPublisher eventPublisher;
    private final JobGate jobGate;

    public ExecutionWorker(
            SubmissionQueueService submissionQueueService,
            CodeExecutionService codeExecutionService,
            ApplicationEventPublisher eventPublisher,
            JobGate jobGate
    ) {
        this.submissionQueueService = submissionQueueService;
        this.codeExecutionService = codeExecutionService;
        this.eventPublisher = eventPublisher;
        this.jobGate = jobGate;
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

            try {
                processJob(job);
            } catch (Exception e) {
                // One bad job must not drop the rest of this tick. The row stays
                // PENDING and SubmissionRecoveryScheduler re-queues it.
                log.error("Execution failed for submission {}", job.submissionId(), e);
            }
        }
    }

    private void processJob(QueuedSubmissionJob job) {
        // Duplicate (recovery re-push) or superseded job: don't spend Judge0 time on it
        if (!jobGate.shouldExecute(job.submissionId())) {
            log.info("Skipping job for submission {}: no longer pending", job.submissionId());
            return;
        }

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

            // Same source compiles the same way every time: one compile error decides
            // every remaining test case, so don't recompile it N times
            if (result.status() == TestCaseResultStatus.COMPILATION_ERROR) {
                for (int i = outcomes.size(); i < job.testCases().size(); i++) {
                    outcomes.add(new SubmissionEvaluatedEvent.TestCaseOutcome(
                            job.testCases().get(i).testCaseId(),
                            TestCaseResultStatus.COMPILATION_ERROR,
                            null,
                            null,
                            null,
                            null,
                            null
                    ));
                }
                break;
            }
        }

        eventPublisher.publishEvent(
                new SubmissionEvaluatedEvent(
                        job.submissionId(),
                        outcomes
                )
        );
    }
}