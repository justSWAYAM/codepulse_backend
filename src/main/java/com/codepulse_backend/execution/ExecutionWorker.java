package com.codepulse_backend.execution;

import com.codepulse_backend.common.enums.TestCaseResultStatus;
import com.codepulse_backend.execution.dto.ExecutionRequest;
import com.codepulse_backend.execution.dto.ExecutionResult;
import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import com.codepulse_backend.execution.event.SubmissionEvaluatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
public class ExecutionWorker {

    private final SubmissionQueueService submissionQueueService;
    private final CodeExecutionService codeExecutionService;
    private final ApplicationEventPublisher eventPublisher;
    private final JobGate jobGate;
    private final Executor judgeExecutor;

    /** Jobs in flight. A job is popped from Redis only once a permit is free. */
    private final Semaphore slots;

    public ExecutionWorker(
            SubmissionQueueService submissionQueueService,
            CodeExecutionService codeExecutionService,
            ApplicationEventPublisher eventPublisher,
            JobGate jobGate,
            @Qualifier("judgeExecutor") Executor judgeExecutor,
            @Value("${execution.worker.concurrency:4}") int concurrency
    ) {
        this.submissionQueueService = submissionQueueService;
        this.codeExecutionService = codeExecutionService;
        this.eventPublisher = eventPublisher;
        this.jobGate = jobGate;
        this.judgeExecutor = judgeExecutor;
        this.slots = new Semaphore(concurrency);
    }

    /**
     * Fills every free slot with a job from the queue; the jobs run in parallel on
     * judgeExecutor. Popping only when a slot is free keeps waiting jobs in Redis
     * (not in memory), so a restart loses at most the jobs in flight, which stay
     * PENDING and are re-queued by SubmissionRecoveryScheduler.
     */
    @Scheduled(fixedDelay = 250)
    public void processQueue() {

        while (slots.tryAcquire()) {

            Object queuedJob;
            try {
                queuedJob = submissionQueueService.pop();
            } catch (RuntimeException e) {
                slots.release();
                throw e;
            }

            if (queuedJob == null) {
                slots.release();
                return;
            }

            QueuedSubmissionJob job =
                    (QueuedSubmissionJob) queuedJob;

            try {
                judgeExecutor.execute(() -> runJob(job));
            } catch (RuntimeException e) {
                // Pool rejected the task: put the job back rather than lose it
                slots.release();
                submissionQueueService.push(job);
                log.warn("Judge pool busy, re-queued submission {}", job.submissionId());
                return;
            }
        }
    }

    private void runJob(QueuedSubmissionJob job) {
        try {
            processJob(job);
        } catch (Exception e) {
            // One bad job must not affect the others. The row stays PENDING and
            // SubmissionRecoveryScheduler re-queues it.
            log.error("Execution failed for submission {}", job.submissionId(), e);
        } finally {
            slots.release();
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