package com.codepulse_backend.session;

import com.codepulse_backend.auth.security.CustomUserDetails;
import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.enums.ContestCandidateStatus;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.exception.AccessDeniedException;
import com.codepulse_backend.common.exception.InvalidStateException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.common.exception.UnauthorizedException;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.entity.ContestCandidate;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final AssessmentSessionRepository sessionRepository;
    private final ContestRepository contestRepository;
    private final ContestCandidateRepository contestCandidateRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /*
     * noRollbackFor: the expired-resume branch finalizes the session and then throws.
     * Without this the exception would roll the finalization back. (Calling the
     * REQUIRES_NEW autoSubmitExpiredSession() from here doesn't help — it's a
     * self-invocation, so Spring's proxy never applies the new transaction.)
     */
    @Transactional(noRollbackFor = InvalidStateException.class)
    public SessionStartResponse startSession(UUID contestId) {
        User candidate = getCurrentUser();

        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("Contest not found with id: " + contestId));

        ContestCandidate assignment = contestCandidateRepository
                .findByContestIdAndCandidateIdForUpdate(contestId, candidate.getId())
                .orElseThrow(() -> new AccessDeniedException(
                        "You are not assigned to this contest"
                ));

        if (contest.getStatus() != ContestStatus.ONGOING) {
            throw new InvalidStateException("The contest is not currently ongoing");
        }

        Instant now = Instant.now(clock);

        var existingSession = sessionRepository
                .findByContestIdAndCandidateIdForUpdate(contestId, candidate.getId());

        if (existingSession.isPresent()) {
            AssessmentSession session = existingSession.get();

            if (session.getStatus() == SessionStatus.IN_PROGRESS) {
                if (!now.isBefore(session.getEndsAt())) {
                    finalizeSession(session, SessionStatus.AUTO_SUBMITTED, now);
                    throw new InvalidStateException("Your assessment time has expired");
                }

                return toStartResponse(session,true);
            }

            throw new InvalidStateException("You have already completed this assessment");
        }

        Instant endsAt = now.plusSeconds(contest.getDurationMinutes() * 60L);

        if (contest.getEndTime().isBefore(endsAt)) {
            endsAt = contest.getEndTime();
        }

        if (!endsAt.isAfter(now)) {
            throw new InvalidStateException("The contest has already ended");
        }

        AssessmentSession session = new AssessmentSession();
        session.setContestId(contestId);
        session.setCandidateId(candidate.getId());
        session.setStartedAt(now);
        session.setEndsAt(endsAt);
        session.setStatus(SessionStatus.IN_PROGRESS);

        AssessmentSession savedSession = sessionRepository.save(session);

        assignment.setStatus(ContestCandidateStatus.IN_PROGRESS);
        contestCandidateRepository.save(assignment);

        auditService.log(
                candidate.getId(),
                SessionAuditAction.SESSION_STARTED,
                "ASSESSMENT_SESSION",
                savedSession.getId(),
                "Candidate started an assessment session"
        );

        eventPublisher.publishEvent(new SessionStartedEvent(
                savedSession.getId(),
                contestId,
                candidate.getId()
        ));

        return toStartResponse(savedSession,false);
    }

    @Transactional
    public SessionResponse getSession(UUID contestId) {
        User candidate = getCurrentUser();

        Instant now = Instant.now(clock);

        // Locked read: this may finalize the session, and must not race the
        // auto-submit scheduler or an explicit submit (double finalize / event)
        Optional<AssessmentSession> optionalSession =
                sessionRepository.findByContestIdAndCandidateIdForUpdate(
                        contestId,
                        candidate.getId()
                );

        if (optionalSession.isEmpty()) {
            return new SessionResponse(
                    null,                         // sessionId
                    contestId,                    // contestId
                    SessionStatus.NOT_YET_STARTED,
                    null,                         // startedAt
                    null,                         // endsAt
                    null,                         // submittedAt
                    now,                          // serverTime
                    0                             // remainingSeconds
            );
        }

        AssessmentSession session = optionalSession.get();

        if (session.getStatus() == SessionStatus.IN_PROGRESS
                && !now.isBefore(session.getEndsAt())) {
            finalizeSession(session, SessionStatus.AUTO_SUBMITTED, now);
        }

        return toResponse(session);
    }

    @Transactional
    public SessionResponse submitSession(UUID contestId) {
        User candidate = getCurrentUser();

        // Scoped to the current candidate, so this is also the ownership check
        AssessmentSession session = sessionRepository
                .findByContestIdAndCandidateIdForUpdate(contestId, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment session not found"
                ));

        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            return toResponse(session);
        }

        Instant now = Instant.now(clock);

        if (!now.isBefore(session.getEndsAt())) {
            finalizeSession(session, SessionStatus.AUTO_SUBMITTED, now);
        } else {
            finalizeSession(session, SessionStatus.SUBMITTED, now);
        }

        return toResponse(session);
    }

    /**
     * Called by the scheduler and when a candidate attempts to resume an
     * already-expired session. Uses its own transaction so finalization
     * persists independently of the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoSubmitExpiredSession(UUID sessionId) {
        AssessmentSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElse(null);

        if (session == null || session.getStatus() != SessionStatus.IN_PROGRESS) {
            return;
        }

        Instant now = Instant.now(clock);

        if (now.isBefore(session.getEndsAt())) {
            return;
        }

        finalizeSession(session, SessionStatus.AUTO_SUBMITTED, now);
    }

    private void finalizeSession(
            AssessmentSession session,
            SessionStatus finalStatus,
            Instant now
    ) {
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            return;
        }

        session.setStatus(finalStatus);
        session.setSubmittedAt(
                now.isAfter(session.getEndsAt()) ? session.getEndsAt() : now
        );

        sessionRepository.save(session);

        contestCandidateRepository
                .findByContestIdAndCandidateId(
                        session.getContestId(),
                        session.getCandidateId()
                )
                .ifPresent(assignment -> {
                    assignment.setStatus(ContestCandidateStatus.COMPLETED);
                    contestCandidateRepository.save(assignment);
                });

        String auditAction = finalStatus == SessionStatus.SUBMITTED
                ? SessionAuditAction.SESSION_SUBMITTED
                : SessionAuditAction.SESSION_AUTO_SUBMITTED;

        auditService.log(
                session.getCandidateId(),
                auditAction,
                "ASSESSMENT_SESSION",
                session.getId(),
                "Assessment session finalized with status " + finalStatus
        );

        eventPublisher.publishEvent(new SessionFinalizedEvent(
                session.getId(),
                session.getContestId(),
                session.getCandidateId(),
                finalStatus
        ));
    }

    private User getCurrentUser() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("User is not authenticated");
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof CustomUserDetails userDetails) {
            return userRepository.findById(userDetails.getId())
                    .orElseThrow(() -> new UnauthorizedException("Authenticated user not found"));
        }

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Authenticated user not found"));
    }

    private SessionStartResponse toStartResponse(
            AssessmentSession session,
            boolean resumed
    ) {
        Instant serverTime = clock.instant();

        long remainingSeconds =
                session.getStatus() == SessionStatus.IN_PROGRESS
                        ? Math.max(
                        0,
                        Duration.between(serverTime, session.getEndsAt()).getSeconds()
                )
                        : 0;

        return new SessionStartResponse(
                session.getId(),
                session.getContestId(),
                session.getStatus(),
                session.getStartedAt(),
                session.getEndsAt(),
                serverTime,
                remainingSeconds,
                resumed
        );
    }

    private SessionResponse toResponse(AssessmentSession session) {
        Instant serverTime = clock.instant();

        long remainingSeconds =
                session.getStatus() == SessionStatus.IN_PROGRESS
                        ? Math.max(
                        0,
                        Duration.between(serverTime, session.getEndsAt()).getSeconds()
                )
                        : 0;

        return new SessionResponse(
                session.getId(),
                session.getContestId(),
                session.getStatus(),
                session.getStartedAt(),
                session.getEndsAt(),
                session.getSubmittedAt(),
                serverTime,
                remainingSeconds
        );
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeSessionOnContestCompletion(UUID sessionId) {
        AssessmentSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElse(null);

        if (session == null || session.getStatus() != SessionStatus.IN_PROGRESS) {
            return;
        }

        finalizeSession(
                session,
                SessionStatus.AUTO_SUBMITTED,
                Instant.now(clock)
        );
    }

    @Transactional(readOnly = true)
    public AssessmentSession requireActiveSession(UUID contestId, UUID candidateId) {
        AssessmentSession session = sessionRepository.findByContestIdAndCandidateId(contestId, candidateId)
                .orElseThrow(() -> new AccessDeniedException("No active assessment session"));
        
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new InvalidStateException("Assessment session is not in progress");
        }
        
        if (!Instant.now(clock).isBefore(session.getEndsAt())) {
            throw new InvalidStateException("Assessment session has expired");
        }
        
        return session;
    }
}