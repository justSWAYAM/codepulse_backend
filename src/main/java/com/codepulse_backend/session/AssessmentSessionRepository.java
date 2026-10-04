package com.codepulse_backend.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentSessionRepository
        extends JpaRepository<AssessmentSession, UUID> {

    Optional<AssessmentSession> findByContestIdAndCandidateId(
            UUID contestId,
            UUID candidateId
    );

    boolean existsByContestId(UUID contestId);

    /**
     * Locking variant used by start/submit so the row is read under the lock
     * (loading it unlocked first and locking afterwards returns the stale
     * cached instance, racing the auto-submit scheduler).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s
        FROM AssessmentSession s
        WHERE s.contestId = :contestId
          AND s.candidateId = :candidateId
        """)
    Optional<AssessmentSession> findByContestIdAndCandidateIdForUpdate(
            @Param("contestId") UUID contestId,
            @Param("candidateId") UUID candidateId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s
        FROM AssessmentSession s
        WHERE s.id = :sessionId
        """)
    Optional<AssessmentSession> findByIdForUpdate(
            @Param("sessionId") UUID sessionId
    );

    List<AssessmentSession> findAllByStatusAndEndsAtLessThanEqual(
            SessionStatus status,
            Instant cutoff
    );

    List<AssessmentSession> findAllByContestIdAndStatus(
            UUID contestId,
            SessionStatus status
    );
    Optional<AssessmentSession> findByContestIdAndCandidateIdAndStatus(
            UUID contestId,
            UUID candidateId,
            SessionStatus status
    );

    /** Module 9 readiness: every session of a contest (tens to a few hundred rows). */
    List<AssessmentSession> findAllByContestId(UUID contestId);
}
