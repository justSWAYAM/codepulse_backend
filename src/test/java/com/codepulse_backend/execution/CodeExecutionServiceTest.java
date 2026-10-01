package com.codepulse_backend.execution;

import com.codepulse_backend.common.enums.TestCaseResultStatus;
import com.codepulse_backend.execution.dto.ExecutionRequest;
import com.codepulse_backend.execution.dto.ExecutionResult;
import com.codepulse_backend.execution.dto.Judge0StatusResponse;
import com.codepulse_backend.execution.dto.Judge0SubmissionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodeExecutionServiceTest {

    @Mock
    private Judge0ClientService judge0ClientService;

    private CodeExecutionService service() {
        return new CodeExecutionService(judge0ClientService);
    }

    @Test
    void accepted_shouldReturnPassed() {

        mockJudge0Result(3, "Accepted", "hello\n");

        ExecutionResult result = service().execute(request());

        assertEquals(TestCaseResultStatus.PASSED, result.status());
        assertEquals("hello\n", result.stdout());
    }

    @Test
    void wrongAnswer_shouldReturnWrongAnswer() {

        mockJudge0Result(4, "Wrong Answer", "wrong\n");

        ExecutionResult result = service().execute(request());

        assertEquals(
                TestCaseResultStatus.WRONG_ANSWER,
                result.status()
        );
    }

    @Test
    void timeLimitExceeded_shouldReturnTle() {

        mockJudge0Result(5, "Time Limit Exceeded", null);

        ExecutionResult result = service().execute(request());

        assertEquals(
                TestCaseResultStatus.TIME_LIMIT_EXCEEDED,
                result.status()
        );
    }

    @Test
    void compilationError_shouldReturnCompilationError() {

        mockJudge0Result(6, "Compilation Error", null);

        ExecutionResult result = service().execute(request());

        assertEquals(
                TestCaseResultStatus.COMPILATION_ERROR,
                result.status()
        );
    }

    @Test
    void memoryLimitExceeded_shouldReturnMle() {

        mockJudge0Result(15, "Memory Limit Exceeded", null);

        ExecutionResult result = service().execute(request());

        assertEquals(
                TestCaseResultStatus.MEMORY_LIMIT_EXCEEDED,
                result.status()
        );
    }

    @Test
    void runtimeError_shouldReturnRuntimeError() {

        mockJudge0Result(7, "Runtime Error", null);

        ExecutionResult result = service().execute(request());

        assertEquals(
                TestCaseResultStatus.RUNTIME_ERROR,
                result.status()
        );
    }

    @Test
    void unknownStatus_shouldReturnSystemError() {

        mockJudge0Result(13, "Internal Error", null);

        ExecutionResult result = service().execute(request());

        assertEquals(
                TestCaseResultStatus.SYSTEM_ERROR,
                result.status()
        );
    }

    @Test
    void outputLongerThanLimit_shouldBeTruncated() {

        String longOutput = "a".repeat(100_000);

        mockJudge0Result(
                3,
                "Accepted",
                longOutput
        );

        ExecutionResult result = service().execute(request());

        assertNotNull(result.stdout());
        assertTrue(result.stdout().contains("... [truncated]"));
        assertEquals(
                65_536 + "\n... [truncated]".length(),
                result.stdout().length()
        );
    }

    @Test
    void executionTime_shouldBeConvertedFromSecondsToMilliseconds() {

        Judge0StatusResponse response =
                new Judge0StatusResponse(
                        "hello\n",
                        null,
                        null,
                        "0.068",
                        9336L,
                        new Judge0StatusResponse.Judge0Status(
                                3,
                                "Accepted"
                        )
                );

        when(judge0ClientService.submitCode(any()))
                .thenReturn("test-token");

        when(judge0ClientService.pollResult("test-token"))
                .thenReturn(response);

        ExecutionResult result = service().execute(request());

        assertEquals(68L, result.executionTimeMs());
        assertEquals(9336L, result.memoryUsedKb());
    }

    private void mockJudge0Result(
            int statusId,
            String description,
            String stdout
    ) {

        Judge0StatusResponse response =
                new Judge0StatusResponse(
                        stdout,
                        null,
                        null,
                        "0.068",
                        9336L,
                        new Judge0StatusResponse.Judge0Status(
                                statusId,
                                description
                        )
                );

        when(judge0ClientService.submitCode(any()))
                .thenReturn("test-token");

        when(judge0ClientService.pollResult("test-token"))
                .thenReturn(response);
    }

    private ExecutionRequest request() {

        return new ExecutionRequest(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                "print(\"hello\")",
                "PYTHON",
                "",
                "expected output",
                5000,
                256000
        );
    }
}