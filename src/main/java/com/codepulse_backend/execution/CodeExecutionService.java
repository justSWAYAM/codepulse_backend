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

            Judge0SubmissionRequest judge0Request =
                    new Judge0SubmissionRequest(
                            request.sourceCode(),
                            language.getJudge0LanguageId(),
                            request.stdin(),
                            request.timeLimitMs() / 1000.0,
                            request.memoryLimitKb()
                    );

            String token =
                    judge0ClientService.submitCode(judge0Request);

            Judge0StatusResponse result =
                    judge0ClientService.pollResult(token);

            return mapToExecutionResult(result);

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
            Judge0StatusResponse response
    ) {

        int statusId = response.status().id();

        TestCaseResultStatus status = switch (statusId) {

            case 3 ->
                    TestCaseResultStatus.PASSED;

            case 4 ->
                    TestCaseResultStatus.WRONG_ANSWER;

            case 5 ->
                    TestCaseResultStatus.TIME_LIMIT_EXCEEDED;

            case 6 ->
                    TestCaseResultStatus.COMPILATION_ERROR;

            case 15 ->
                    TestCaseResultStatus.MEMORY_LIMIT_EXCEEDED;

            case 7, 8, 9, 10, 11, 12, 14 ->
                    TestCaseResultStatus.RUNTIME_ERROR;

            default ->
                    TestCaseResultStatus.SYSTEM_ERROR;
        };

        return new ExecutionResult(
                status,
                truncate(response.stdout(), MAX_STDOUT_LENGTH),
                truncate(response.stderr(), MAX_STDERR_LENGTH),
                truncate(response.compileOutput(), MAX_COMPILE_OUTPUT_LENGTH),
                convertExecutionTime(response.time()),
                response.memory()
        );
    }

    private Long convertExecutionTime(String time) {

        if (time == null || time.isBlank()) {
            return null;
        }

        return Math.round(Double.parseDouble(time) * 1000);
    }

    private String truncate(String value, int maxLength) {

        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength)
                + "\n... [truncated]";
    }
}