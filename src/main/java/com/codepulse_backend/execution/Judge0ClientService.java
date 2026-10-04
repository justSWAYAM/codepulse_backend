package com.codepulse_backend.execution;

import com.codepulse_backend.config.Judge0Properties;
import com.codepulse_backend.execution.dto.Judge0StatusResponse;
import com.codepulse_backend.execution.dto.Judge0SubmissionRequest;
import com.codepulse_backend.common.exception.Judge0IntegrationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * Judge0 is polled (wait=false + GET by token) rather than using wait=true,
 * so one slow program never pins a Judge0 HTTP worker.
 */
@Slf4j
@Service
public class Judge0ClientService {

    private static final long SUBMIT_RETRY_BACKOFF_MS = 500;

    private final WebClient judge0WebClient;
    private final Judge0Properties properties;

    public Judge0ClientService(
            WebClient judge0WebClient,
            Judge0Properties properties
    ) {
        this.judge0WebClient = judge0WebClient;
        this.properties = properties;
    }

    public String submitCode(Judge0SubmissionRequest request) {

        int attempts = 0;

        while (attempts < 2) {
            attempts++;

            try {
                Map<?, ?> response = judge0WebClient
                        .post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/submissions")
                                .queryParam("wait", false)
                                .queryParam("base64_encoded", true)
                                .build())
                        // Base64 keeps non-UTF-8 program output from breaking Judge0's JSON
                        .bodyValue(request.base64Encoded())
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (response == null || response.get("token") == null) {
                    throw new Judge0IntegrationException(
                            "Judge0 did not return a submission token"
                    );
                }

                return response.get("token").toString();

            } catch (Exception e) {

                // A 4xx means the request itself is bad; retrying will not help.
                boolean clientError = e instanceof WebClientResponseException wcre
                        && wcre.getStatusCode().is4xxClientError();

                if (attempts >= 2 || clientError) {
                    throw new Judge0IntegrationException(
                            "Failed to submit code to Judge0",
                            e
                    );
                }

                log.warn("Judge0 submit failed, retrying once: {}", e.getMessage());
                sleepBeforeRetry();
            }
        }

        throw new Judge0IntegrationException(
                "Failed to submit code to Judge0"
        );
    }

    public Judge0StatusResponse pollResult(String token) {
        return pollResult(token, properties.getMaxPollAttempts() * properties.getPollIntervalMs());
    }

    /**
     * Polls until Judge0 reports a final status or {@code budgetMs} runs out.
     * The caller sizes the budget from the test case's time limits plus queue time.
     */
    public Judge0StatusResponse pollResult(String token, long budgetMs) {

        long deadline = System.currentTimeMillis() + budgetMs;

        while (true) {

            Judge0StatusResponse response;

            try {
                response = judge0WebClient
                        .get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/submissions/{token}")
                                .queryParam(
                                        "fields",
                                        "token,status,stdout,stderr,compile_output,time,memory"
                                )
                                .queryParam("base64_encoded", true)
                                .build(token))
                        .retrieve()
                        .bodyToMono(Judge0StatusResponse.class)
                        .block();

            } catch (Exception e) {
                throw new Judge0IntegrationException(
                        "Failed to poll Judge0",
                        e
                );
            }

            if (response == null || response.status() == null) {
                throw new Judge0IntegrationException(
                        "Judge0 returned an invalid response"
                );
            }

            int statusId = response.status().id();
            log.debug("Judge0 token={} status id={} ({})",
                    token, statusId, response.status().description());

            if (statusId >= 3) {
                return response.base64Decoded();
            }

            if (System.currentTimeMillis() + properties.getPollIntervalMs() > deadline) {
                break;
            }

            try {
                Thread.sleep(properties.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                throw new Judge0IntegrationException(
                        "Judge0 polling was interrupted",
                        e
                );
            }
        }

        throw new Judge0IntegrationException(
                "Execution timed out"
        );
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(SUBMIT_RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Judge0IntegrationException("Judge0 retry was interrupted", e);
        }
    }
}
