package com.codepulse_backend.submission.service;

import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.config.SubmissionProperties;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.session.SessionStatus;
import com.codepulse_backend.submission.entity.Submission;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SubmissionAccessGuard {

    private final SubmissionProperties properties;
    private final Clock clock;

    public void assertCandidateOwns(
            Submission submission,
            UUID candidateId
    ) {
        if (!submission.getCandidateId().equals(candidateId)) {
            throw new ResourceNotFoundException("SUBMISSION_NOT_FOUND");
        }
    }

    public void assertWithinDeadline(
            AssessmentSession session
    ) {
        Instant now = Instant.now(clock);

        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new IllegalStateException("SESSION_NOT_IN_PROGRESS");
        }

        Instant deadline = session.getEndsAt()
                .plusSeconds(properties.getGraceSeconds());

        if (now.isAfter(deadline)) {
            throw new IllegalStateException("SESSION_DEADLINE_PASSED");
        }
    }

    public ViewerRole resolveViewerRole(
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("UNAUTHENTICATED");
        }

        boolean admin = authentication.getAuthorities()
                .stream()
                .anyMatch(a ->
                        a.getAuthority().equals("ROLE_ADMIN"));

        if (admin) {
            return ViewerRole.ADMIN;
        }

        boolean evaluator = authentication.getAuthorities()
                .stream()
                .anyMatch(a ->
                        a.getAuthority().equals("ROLE_EVALUATOR"));

        if (evaluator) {
            return ViewerRole.EVALUATOR;
        }

        return ViewerRole.CANDIDATE;
    }

    public enum ViewerRole {
        CANDIDATE,
        EVALUATOR,
        ADMIN
    }
}