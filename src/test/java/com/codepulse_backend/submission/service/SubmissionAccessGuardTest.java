package com.codepulse_backend.submission.service;

import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.config.SubmissionProperties;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.session.SessionStatus;
import com.codepulse_backend.submission.entity.Submission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SubmissionAccessGuardTest {

    private static final Instant NOW =
            Instant.parse("2026-10-01T00:00:00Z");

    private SubmissionAccessGuard guard;

    @BeforeEach
    void setUp() {
        SubmissionProperties properties =
                new SubmissionProperties();

        properties.setGraceSeconds(5);

        Clock clock = Clock.fixed(
                NOW,
                ZoneOffset.UTC
        );

        guard = new SubmissionAccessGuard(
                properties,
                clock
        );
    }

    @Test
    void otherCandidateMustReceiveNotFound() {
        UUID ownerId = UUID.randomUUID();
        UUID otherCandidateId = UUID.randomUUID();

        Submission submission = new Submission();
        submission.setCandidateId(ownerId);

        assertThrows(
                ResourceNotFoundException.class,
                () -> guard.assertCandidateOwns(
                        submission,
                        otherCandidateId
                )
        );
    }

    @Test
    void ownerCanAccessOwnSubmission() {
        UUID candidateId = UUID.randomUUID();

        Submission submission = new Submission();
        submission.setCandidateId(candidateId);

        assertDoesNotThrow(() ->
                guard.assertCandidateOwns(
                        submission,
                        candidateId
                )
        );
    }

    @Test
    void exactlyAtEndsAtIsAllowed() {
        AssessmentSession session =
                createSession(NOW);

        assertDoesNotThrow(() ->
                guard.assertWithinDeadline(session)
        );
    }

    @Test
    void exactlyAtGraceBoundaryIsAllowed() {
        AssessmentSession session =
                createSession(
                        NOW.minusSeconds(5)
                );

        assertDoesNotThrow(() ->
                guard.assertWithinDeadline(session)
        );
    }

    @Test
    void sixSecondsAfterDeadlineIsRejected() {
        AssessmentSession session =
                createSession(
                        NOW.minusSeconds(6)
                );

        assertThrows(
                IllegalStateException.class,
                () -> guard.assertWithinDeadline(session)
        );
    }

    @Test
    void nonInProgressSessionIsRejected() {
        AssessmentSession session =
                createSession(NOW);

        session.setStatus(SessionStatus.SUBMITTED);

        assertThrows(
                IllegalStateException.class,
                () -> guard.assertWithinDeadline(session)
        );
    }

    private AssessmentSession createSession(
            Instant endsAt
    ) {
        AssessmentSession session =
                new AssessmentSession();

        session.setContestId(UUID.randomUUID());
        session.setCandidateId(UUID.randomUUID());
        session.setStartedAt(
                endsAt.minusSeconds(60)
        );
        session.setEndsAt(endsAt);
        session.setStatus(SessionStatus.IN_PROGRESS);

        return session;
    }
}