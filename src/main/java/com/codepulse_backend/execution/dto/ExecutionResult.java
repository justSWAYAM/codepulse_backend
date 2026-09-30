package com.codepulse_backend.execution.dto;

import com.codepulse_backend.common.enums.TestCaseResultStatus;

public record ExecutionResult(
        TestCaseResultStatus status,
        String stdout,
        String stderr,
        String compileOutput,
        Long executionTimeMs,
        Long memoryUsedKb
) {}