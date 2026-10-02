package com.codepulse_backend.execution;

import com.codepulse_backend.common.enums.SupportedLanguage;
import com.codepulse_backend.common.enums.TestCaseResultStatus;
import com.codepulse_backend.common.exception.Judge0IntegrationException;
import com.codepulse_backend.execution.dto.ExecutionRequest;
import com.codepulse_backend.execution.dto.ExecutionResult;
import com.codepulse_backend.execution.dto.Judge0StatusResponse;
import com.codepulse_backend.execution.dto.Judge0SubmissionRequest;
import org.springframework.stereotype.Service;

@Service
public class CodeExecutionService {

    private static final int MAX_STDOUT_LENGTH = 65_536;
    private static final int MAX_STDERR_LENGTH = 8_192;
    private static final int MAX_COMPILE_OUTPUT_LENGTH = 8_192;

    /** Judge0's default MAX_WALL_TIME_LIMIT is 20 s. */
    private static final double MAX_WALL_TIME_SECONDS = 20.0;
    /** Time a test case may wait in Judge0's own queue under exam load, on top of its run time. */
    private static final long QUEUE_ALLOWANCE_MS = 60_000;

    private final Judge0ClientService judge0ClientService;

    public CodeExecutionService(
            Judge0ClientService judge0ClientService
    ) {
        this.judge0ClientService = judge0ClientService;
    }

    public ExecutionResult execute(ExecutionRequest request) {

        try {
            SupportedLanguage language =
                    SupportedLanguage.fromName(request.languageName());

            double cpuSeconds = request.timeLimitMs() / 1000.0;
            // Wall clock covers JVM/interpreter start-up and blocking I/O on top of CPU time
            double wallSeconds = Math.min(cpuSeconds * 2 + 1, MAX_WALL_TIME_SECONDS);

            Judge0SubmissionRequest judge0Request =
                    new Judge0SubmissionRequest(
                            request.sourceCode(),
                            language.getJudge0LanguageId(),
                            request.stdin(),
                            cpuSeconds,
                            wallSeconds,
                            request.memoryLimitKb(),
                            request.expectedOutput()
                    );

            String token =
                    judge0ClientService.submitCode(judge0Request);

            // A fixed ~10 s budget turned legitimate long runs into SYSTEM_ERROR
            long pollBudgetMs = (long) (wallSeconds * 1000) + QUEUE_ALLOWANCE_MS;

            Judge0StatusResponse result =
                    judge0ClientService.pollResult(token, pollBudgetMs);

            return mapToExecutionResult(
                    result,
                    request.languageName(),
                    request.memoryLimitKb()
            );

        } catch (Judge0IntegrationException e) {
            return new ExecutionResult(
                    TestCaseResultStatus.SYSTEM_ERROR,
                    null,
                    e.getMessage(),
                    null,
                    null,
                    null
            );
        }
    }

    private ExecutionResult mapToExecutionResult(
            Judge0StatusResponse response,
            String languageName,
            long memoryLimitKb
    ) {

        int statusId = response.status().id();

        TestCaseResultStatus status;

        /*
         * Judge0 1.13.1 reports Python syntax errors as
         * Runtime Error (11 / NZEC) instead of Compilation Error (6).
         *
         * Normalize clear Python syntax/indentation errors so that
         * the application exposes the status required by Module 8.
         */
        if (statusId == 11
                && "PYTHON".equalsIgnoreCase(languageName)
                && isPythonSyntaxError(response.stderr())) {

            status = TestCaseResultStatus.COMPILATION_ERROR;

        } else {

            status = switch (statusId) {

                case 3 ->
                        TestCaseResultStatus.PASSED;

                case 4 ->
                        TestCaseResultStatus.WRONG_ANSWER;

                case 5 ->
                        TestCaseResultStatus.TIME_LIMIT_EXCEEDED;

                case 6 ->
                        TestCaseResultStatus.COMPILATION_ERROR;

                // Judge0 1.13 has no memory-limit status: running out of memory
                // surfaces as a runtime error (SIGSEGV / NZEC).
                case 7, 8, 9, 10, 11, 12 ->
                        isOutOfMemory(response, memoryLimitKb)
                                ? TestCaseResultStatus.MEMORY_LIMIT_EXCEEDED
                                : TestCaseResultStatus.RUNTIME_ERROR;

                // 13 Internal Error and 14 Exec Format Error are sandbox failures,
                // not the candidate's fault.

                default ->
                        TestCaseResultStatus.SYSTEM_ERROR;
            };
        }

        return new ExecutionResult(
                status,
                truncate(response.stdout(), MAX_STDOUT_LENGTH),
                truncate(response.stderr(), MAX_STDERR_LENGTH),
                truncate(response.compileOutput(), MAX_COMPILE_OUTPUT_LENGTH),
                convertExecutionTime(response.time()),
                response.memory()
        );
    }

    private boolean isOutOfMemory(Judge0StatusResponse response, long memoryLimitKb) {
        if (response.memory() != null && memoryLimitKb > 0
                && response.memory() >= memoryLimitKb * 0.95) {
            return true;
        }

        String stderr = response.stderr();
        return stderr != null
                && (stderr.contains("MemoryError")
                || stderr.contains("OutOfMemoryError")
                || stderr.contains("std::bad_alloc"));
    }

    private boolean isPythonSyntaxError(String stderr) {

        if (stderr == null) {
            return false;
        }

        return stderr.contains("SyntaxError:")
                || stderr.contains("IndentationError:")
                || stderr.contains("TabError:");
    }

    private Long convertExecutionTime(String time) {

        if (time == null || time.isBlank()) {
            return null;
        }

        try {
            return Math.round(Double.parseDouble(time) * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String value, int maxLength) {

        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength)
                + "\n... [truncated]";
    }
}