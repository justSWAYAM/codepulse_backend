package com.codepulse_backend.submission.service;

import com.codepulse_backend.config.SubmissionProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class RunConcurrencyLimiter {

    private final Semaphore semaphore;
    private final long waitMs;

    public RunConcurrencyLimiter(SubmissionProperties properties) {
        this.semaphore = new Semaphore(properties.getMaxConcurrentRuns());
        this.waitMs = properties.getRunPermitWaitMs();
    }

    public boolean tryAcquire() {
        try {
            return semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        semaphore.release();
    }
}