package com.codepulse_backend.result.controller;

import com.codepulse_backend.auth.security.CustomUserDetails;
import com.codepulse_backend.common.dto.ApiResponse;
import com.codepulse_backend.result.dto.ManualEvaluationRequest;
import com.codepulse_backend.result.dto.ResultResponse;
import com.codepulse_backend.result.service.ManualEvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Lives under /submissions but belongs to Module 9. Unlike the Module 8 submission
 * endpoints, the response is wrapped in ApiResponse.
 */
@RestController
@RequiredArgsConstructor
public class ManualEvaluationController {

    private final ManualEvaluationService manualEvaluationService;

    @PostMapping("/api/submissions/{submissionId}/evaluate")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'ADMIN')")
    public ApiResponse<ResultResponse> evaluate(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID submissionId,
            @Valid @RequestBody ManualEvaluationRequest request) {
        ResultResponse result = manualEvaluationService.evaluate(submissionId, request, principal.getId());
        return new ApiResponse<>(true, result, "Score updated", Instant.now(), null);
    }
}
