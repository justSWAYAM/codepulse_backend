package com.codepulse_backend.library.imports.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "codepulse.import")
public record ImportProperties(
        @DefaultValue("1048576") long maxPayloadBytes,
        @DefaultValue("200") int maxQuestions,
        int defaultTimeLimitMs,
        int defaultMemoryLimitKb) {
}
