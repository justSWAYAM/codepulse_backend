package com.codepulse_backend.contest.controller;

import com.codepulse_backend.common.dto.ApiResponse;
import com.codepulse_backend.common.dto.PagedResponse;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.contest.dto.*;
import com.codepulse_backend.contest.service.ContestService;
import com.codepulse_backend.question.dto.AddFromLibraryRequest;
import com.codepulse_backend.question.service.QuestionLibraryService;
import com.codepulse_backend.user.dto.UserSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/contests")
@RequiredArgsConstructor
public class ContestController {

    private final ContestService contestService;
    private final QuestionLibraryService questionLibraryService;

    @GetMapping
    public ApiResponse<PagedResponse<ContestResponse>> listContests(
            @RequestParam(required = false) ContestStatus status,
            Pageable pageable) {
        PagedResponse<ContestResponse> result = contestService.getContests(status, pageable);
        return new ApiResponse<>(true, result, "Contests retrieved successfully", Instant.now(), null);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ContestResponse> createContest(
            @Valid @RequestBody CreateContestRequest request) {
        ContestResponse created = contestService.createContest(request);
        return new ApiResponse<>(true, created, "Contest created successfully", Instant.now(), null);
    }

    @GetMapping("/{id}")
    public ApiResponse<ContestDetailResponse> getContest(@PathVariable UUID id) {
        ContestDetailResponse detail = contestService.getContestDetail(id);
        return new ApiResponse<>(true, detail, "Contest retrieved successfully", Instant.now(), null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ContestResponse> updateContest(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateContestRequest request) {
        ContestResponse updated = contestService.updateContest(id, request);
        return new ApiResponse<>(true, updated, "Contest updated successfully", Instant.now(), null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteContest(@PathVariable UUID id) {
        contestService.deleteContest(id);
        return new ApiResponse<>(true, null, "Contest deleted successfully", Instant.now(), null);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ContestResponse> publishContest(@PathVariable UUID id) {
        ContestResponse published = contestService.publishContest(id);
        return new ApiResponse<>(true, published, "Contest published successfully", Instant.now(), null);
    }

    @PostMapping("/{id}/candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AssignCandidatesResult> assignCandidates(
            @PathVariable UUID id,
            @Valid @RequestBody AssignCandidatesRequest request) {
        AssignCandidatesResult result = contestService.assignCandidates(id, request);
        return new ApiResponse<>(true, result, "Candidates assigned", Instant.now(), null);
    }

    @DeleteMapping("/{id}/candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UnassignCandidatesResult> unassignCandidates(
            @PathVariable UUID id,
            @Valid @RequestBody UnassignCandidatesRequest request) {
        UnassignCandidatesResult result = contestService.unassignCandidates(id, request);
        return new ApiResponse<>(true, result, "Candidates unassigned", Instant.now(), null);
    }

    @GetMapping("/{id}/candidates")
    @PreAuthorize("hasRole('ADMIN') or hasRole('EVALUATOR')")
    public ApiResponse<List<UserSummaryResponse>> getCandidates(@PathVariable UUID id) {
        List<UserSummaryResponse> candidates = contestService.getCandidates(id);
        return new ApiResponse<>(true, candidates, "Candidates retrieved successfully", Instant.now(), null);
    }

    /**
     * POST /api/contests/{id}/questions/from-library
     *
     * Deep-copies the selected library questions into this contest.
     * Already-copied questions are silently skipped (idempotent).
     * The contest must be in DRAFT or PUBLISHED status.
     * Restricted to ADMIN and EVALUATOR roles.
     */
    @PostMapping("/{id}/questions/from-library")
    @PreAuthorize("hasAnyRole('ADMIN', 'EVALUATOR')")
    public ApiResponse<Void> addQuestionsFromLibrary(
            @PathVariable UUID id,
            @Valid @RequestBody AddFromLibraryRequest request) {
        int copied = questionLibraryService.addQuestionsToContest(id, request.questionIds());
        String msg = copied + " question(s) copied to contest from library";
        return new ApiResponse<>(true, null, msg, Instant.now(), null);
    }
}
