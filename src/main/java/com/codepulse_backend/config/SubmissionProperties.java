package com.codepulse_backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "submission")
public class SubmissionProperties {

    private int graceSeconds = 5;
    private int maxSubmitPerQuestion = 30;
    private int maxRunPerQuestion = 100;
    private int maxConcurrentRuns = 10;
    private long runPermitWaitMs = 2000;
    private int storedOutputMaxChars = 10000;
    private int storedStderrMaxChars = 4000;

    private Recovery recovery = new Recovery();
    private RateLimit rateLimit = new RateLimit();

    @Getter
    @Setter
    public static class Recovery {
        private long intervalMs = 60000;
        private int staleAfterMinutes = 10;
        private int maxQueueAttempts = 3;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;
        private int loginPerMinute = 60;
        private int runPerMinute = 20;
        private int submitPerMinute = 10;
    }
}