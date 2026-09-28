package com.codepulse_backend.session;

import com.codepulse_backend.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/contests/{contestId}/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @PostMapping("/start")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<SessionStartResponse>> startSession(
            @PathVariable UUID contestId
    ) {
        SessionStartResponse response = sessionService.startSession(contestId);

        return ResponseEntity.ok(
                ApiResponse.success(
                        response,
                        "Assessment session started successfully",
                        null
                )
        );
    }

    @GetMapping
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<SessionResponse>> getSession(
            @PathVariable UUID contestId
    ) {
        SessionResponse response = sessionService.getSession(contestId);

        return ResponseEntity.ok(
                ApiResponse.success(
                        response,
                        "Assessment session retrieved successfully",
                        null
                )
        );
    }

    @PostMapping("/submit")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<SessionResponse>> submitSession(
            @PathVariable UUID contestId
    ) {
        SessionResponse response = sessionService.submitSession(contestId);

        return ResponseEntity.ok(
                ApiResponse.success(
                        response,
                        "Assessment session submitted successfully",
                        null
                )
        );
    }
}