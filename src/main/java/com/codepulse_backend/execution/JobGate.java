package com.codepulse_backend.execution;

import java.util.UUID;

/**
 * Lets the worker skip a queued job whose submission no longer needs judging
 * (already evaluated, rejudge superseded it, recovery gave up). Implemented by
 * the submission module so execution does not depend on it directly.
 */
@FunctionalInterface
public interface JobGate {

    boolean shouldExecute(UUID submissionId);
}
