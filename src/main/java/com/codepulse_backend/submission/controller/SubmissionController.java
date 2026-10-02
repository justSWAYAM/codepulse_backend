package com.codepulse_backend.submission.controller;

import com.codepulse_backend.auth.security.CustomUserDetails;
import com.codepulse_backend.common.dto.PagedResponse;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.submission.dto.RunCodeRequest;
import com.codepulse_backend.submission.dto.ContestSubmissionRowResponse;
import com.codepulse_backend.submission.dto.SubmissionSummaryResponse;
import com.codepulse_backend.submission.dto.SubmitCodeRequest;
import com.codepulse_backend.submission.service.SubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;

    @PostMapping("/api/submissions/run")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<?> run(
            Authentication authentication,
            @Valid @RequestBody RunCodeRequest request
    ) {
        UUID candidateId = getCandidateId(authentication);

        var submission = submissionService.run(candidateId, request);

        return ResponseEntity.ok(
                SubmissionSummaryResponse.from(submission, false)
        );
    }

    @PostMapping("/api/submissions/submit")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<?> submit(
            Authentication authentication,
            @Valid @RequestBody SubmitCodeRequest request
    ) {
        UUID candidateId = getCandidateId(authentication);

        var submission = submissionService.submit(candidateId, request);

        return ResponseEntity.accepted()
                .body(SubmissionSummaryResponse.from(submission, false));
    }

    @GetMapping("/api/submissions/{submissionId}")
    public ResponseEntity<?> getSubmission(
            Authentication authentication,
            @PathVariable UUID submissionId
    ) {
        UUID viewerId = getCandidateId(authentication);

        return ResponseEntity.ok(
                submissionService.getSubmission(authentication, viewerId, submissionId)
        );
    }

    @GetMapping("/api/questions/{questionId}/submissions/me")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<PagedResponse<SubmissionSummaryResponse>> getMyHistory(
            Authentication authentication,
            @PathVariable UUID questionId,
            @RequestParam(required = false) SubmissionType type,
            @PageableDefault(
                    sort = "submittedAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {
        UUID candidateId = getCandidateId(authentication);

        return ResponseEntity.ok(
                submissionService.getMyHistory(
                        candidateId,
                        questionId,
                        type,
                        pageable
                )
        );
    }

    @GetMapping("/api/contests/{contestId}/submissions")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'ADMIN')")
    public ResponseEntity<PagedResponse<ContestSubmissionRowResponse>> listContestSubmissions(
            @PathVariable UUID contestId,
            @RequestParam(required = false) UUID candidateId,
            @RequestParam(required = false) UUID questionId,
            @RequestParam(required = false) SubmissionType type,
            @RequestParam(required = false) SubmissionStatus status,
            @PageableDefault(
                    size = 20,
                    sort = "submittedAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable) {

        return ResponseEntity.ok(
                submissionService.getContestSubmissions(
                        contestId,
                        candidateId,
                        questionId,
                        type,
                        status,
                        pageable
                )
        );
    }

    @PostMapping("/api/submissions/{id}/rejudge")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'ADMIN')")
    public ResponseEntity<Void> rejudgeSubmission(
            Authentication authentication,
            @PathVariable UUID id
    ) {
        submissionService.rejudge(id, getCandidateId(authentication));
        return ResponseEntity.accepted().build();
    }

    private UUID getCandidateId(Authentication authentication) {
        CustomUserDetails userDetails =
                (CustomUserDetails) authentication.getPrincipal();

        return userDetails.getId();
    }
}