package com.codepulse_backend.result.controller;

import com.codepulse_backend.auth.security.CustomUserDetails;
import com.codepulse_backend.common.dto.ApiResponse;
import com.codepulse_backend.result.dto.LeaderboardResponse;
import com.codepulse_backend.result.dto.MyResultResponse;
import com.codepulse_backend.result.dto.PublishResultsRequest;
import com.codepulse_backend.result.dto.ResultResponse;
import com.codepulse_backend.result.dto.UnpublishResultsRequest;
import com.codepulse_backend.result.service.ResultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/contests/{contestId}/results")
@RequiredArgsConstructor
public class ResultController {

    private final ResultService resultService;

    @GetMapping
    @PreAuthorize("hasAnyRole('EVALUATOR', 'ADMIN')")
    public ApiResponse<LeaderboardResponse> leaderboard(@PathVariable UUID contestId) {
        return ok(resultService.getLeaderboard(contestId), "Results retrieved");
    }

    @GetMapping("/candidates/{candidateId}")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'ADMIN')")
    public ApiResponse<ResultResponse> candidateResult(
            @PathVariable UUID contestId,
            @PathVariable UUID candidateId) {
        return ok(resultService.getCandidateResult(contestId, candidateId), "Result retrieved");
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ApiResponse<MyResultResponse> myResult(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID contestId) {
        return ok(resultService.getMyResult(contestId, principal.getId()), "Result retrieved");
    }

    @PostMapping("/recompute")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<LeaderboardResponse> recompute(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID contestId) {
        return ok(resultService.recomputeContest(contestId, principal.getId()), "Results recomputed");
    }

    @PostMapping("/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<LeaderboardResponse> publish(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID contestId,
            @RequestBody(required = false) PublishResultsRequest request) {
        return ok(resultService.publish(contestId, request, principal.getId()), "Results published");
    }

    @PostMapping("/unpublish")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<LeaderboardResponse> unpublish(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID contestId,
            @Valid @RequestBody UnpublishResultsRequest request) {
        return ok(resultService.unpublish(contestId, request, principal.getId()), "Results unpublished");
    }

    private static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, Instant.now(), null);
    }
}
