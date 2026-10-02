package com.codepulse_backend.execution;

import com.codepulse_backend.execution.dto.Judge0StatusResponse;
import com.codepulse_backend.execution.dto.Judge0SubmissionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Judge0ClientServiceTest {

    @Autowired
    private Judge0ClientService judge0ClientService;

    @Test
    void shouldSubmitAndExecutePythonCode() {

        Judge0SubmissionRequest request =
                new Judge0SubmissionRequest(
                        "print(\"hello world\")",
                        71,
                        "",
                        5.0,
                        11.0,
                        256000,
                        "hello world\n" // <--- Added the expectedOutput argument here
                );

        String token = judge0ClientService.submitCode(request);

        assertNotNull(token);
        assertFalse(token.isBlank());

        Judge0StatusResponse result =
                judge0ClientService.pollResult(token);

        assertNotNull(result);
        assertNotNull(result.status());

        assertEquals(3, result.status().id());
        assertEquals("Accepted", result.status().description());
        assertEquals("hello world\n", result.stdout());
    }
}