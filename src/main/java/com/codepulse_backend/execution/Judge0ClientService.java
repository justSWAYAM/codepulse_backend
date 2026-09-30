package com.codepulse_backend.execution;

import com.codepulse_backend.config.Judge0Properties;
import com.codepulse_backend.execution.dto.Judge0StatusResponse;
import com.codepulse_backend.execution.dto.Judge0SubmissionRequest;
import com.codepulse_backend.common.exception.Judge0IntegrationException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class Judge0ClientService {

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
                                .build())
                        .bodyValue(request)
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

                if (attempts >= 2) {
                    throw new Judge0IntegrationException(
                            "Failed to submit code to Judge0",
                            e
                    );
                }
            }
        }

        throw new Judge0IntegrationException(
                "Failed to submit code to Judge0"
        );
    }

    public Judge0StatusResponse pollResult(String token) {

        for (int attempt = 1;
             attempt <= properties.getMaxPollAttempts();
             attempt++) {

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
            System.out.println(
                    "JUDGE0 STATUS -> id=" + statusId +
                            ", description=" + response.status().description()
            );

            if (statusId >= 3) {
                return response;
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
}