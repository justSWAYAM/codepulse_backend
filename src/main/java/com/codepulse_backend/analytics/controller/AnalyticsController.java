package com.codepulse_backend.analytics.controller;

import com.codepulse_backend.analytics.dto.ContestAnalyticsResponse;
import com.codepulse_backend.analytics.dto.QuestionAnalyticsResponse;
import com.codepulse_backend.analytics.dto.TestCaseAnalyticsResponse;
import com.codepulse_backend.analytics.service.AnalyticsService;
import com.codepulse_backend.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Staff only, before and after results are published (plan 2.9). */
@RestController
@RequestMapping("/api/contests/{contestId}/analytics")
@PreAuthorize("hasAnyRole('EVALUATOR', 'ADMIN')")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/overview")
    public ApiResponse<ContestAnalyticsResponse> overview(@PathVariable UUID contestId) {
        return ok(analyticsService.getOverview(contestId));
    }

    @GetMapping("/questions")
    public ApiResponse<QuestionAnalyticsResponse> questions(@PathVariable UUID contestId) {
        return ok(analyticsService.getQuestions(contestId));
    }

    @GetMapping("/questions/{questionId}/test-cases")
    public ApiResponse<TestCaseAnalyticsResponse> testCases(@PathVariable UUID contestId, @PathVariable UUID questionId) {
        return ok(analyticsService.getTestCases(contestId, questionId));
    }

    private static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, "Analytics retrieved", Instant.now(), null);
    }
}
