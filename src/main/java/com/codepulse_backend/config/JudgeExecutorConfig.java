package com.codepulse_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Threads that judge queued submissions. Judge0 runs many sandboxes in parallel,
 * so judging one job at a time left it idle and made every candidate wait behind
 * the slowest submission. ExecutionWorker caps jobs in flight at the same size,
 * so this pool never queues work of its own: Redis stays the only waiting room.
 */
@Configuration
public class JudgeExecutorConfig {

    @Bean(name = "judgeExecutor")
    public ThreadPoolTaskExecutor judgeExecutor(
            @Value("${execution.worker.concurrency:4}") int concurrency
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("judge-");
        // Let jobs in flight finish on shutdown; anything cut off stays PENDING for recovery
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
