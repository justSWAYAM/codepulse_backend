package com.codepulse_backend.submission.repository;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.submission.entity.Submission;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubmissionRepository
        extends JpaRepository<Submission, UUID>, JpaSpecificationExecutor<Submission> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s
        FROM Submission s
        WHERE s.id = :id
        """)
    Optional<Submission> findByIdForUpdate(@Param("id") UUID id);

    boolean existsBySessionIdAndQuestionIdAndSubmissionTypeAndStatus(
            UUID sessionId,
            UUID questionId,
            SubmissionType submissionType,
            SubmissionStatus status
    );

    long countBySessionIdAndQuestionIdAndSubmissionType(
            UUID sessionId,
            UUID questionId,
            SubmissionType submissionType
    );

    Page<Submission> findByCandidateIdAndQuestionId(
            UUID candidateId,
            UUID questionId,
            Pageable pageable
    );

    Page<Submission> findByCandidateIdAndQuestionIdAndSubmissionType(
            UUID candidateId,
            UUID questionId,
            SubmissionType submissionType,
            Pageable pageable
    );

    List<Submission> findBySessionIdAndSubmissionTypeAndStatus(
            UUID sessionId,
            SubmissionType submissionType,
            SubmissionStatus status
    );

    long countBySessionIdAndSubmissionTypeAndStatus(
            UUID sessionId,
            SubmissionType submissionType,
            SubmissionStatus status
    );

    @Query("""
        SELECT s
        FROM Submission s
        WHERE s.status = :status
          AND s.queuedAt < :cutoff
          AND s.queueAttempts < :maxAttempts
        """)
    List<Submission> findStalePending(
            @Param("status") SubmissionStatus status,
            @Param("cutoff") Instant cutoff,
            @Param("maxAttempts") int maxAttempts
    );

    /** Stale PENDING rows that have used up their queue attempts. */
    List<Submission> findByStatusAndQueueAttemptsGreaterThanEqualAndQueuedAtBefore(
            SubmissionStatus status,
            int maxAttempts,
            Instant cutoff
    );

    /**
     * Best SUBMIT per question for a session.
     *
     * PostgreSQL DISTINCT ON:
     * - highest score wins
     * - earliest submitted_at wins ties
     * - PENDING and SYSTEM_ERROR are excluded
     */
    @Query(value = """
        SELECT DISTINCT ON (question_id) *
        FROM submissions
        WHERE session_id = :sessionId
          AND submission_type = 'SUBMIT'
          AND status NOT IN ('PENDING', 'SYSTEM_ERROR')
        ORDER BY question_id, score DESC NULLS LAST, submitted_at ASC
        """, nativeQuery = true)
    List<Submission> findCountedSubmissions(
            @Param("sessionId") UUID sessionId
    );

    @Query("""
        SELECT s FROM Submission s 
        WHERE s.sessionId IN (SELECT asess.id FROM AssessmentSession asess WHERE asess.contestId = :contestId)
          AND (:candidateId IS NULL OR s.candidateId = :candidateId)
          AND (:questionId IS NULL OR s.questionId = :questionId)
          AND (:type IS NULL OR s.submissionType = :type)
          AND (:status IS NULL OR s.status = :status)
    """)
    Page<Submission> findContestSubmissions(
            @Param("contestId") UUID contestId,
            @Param("candidateId") UUID candidateId,
            @Param("questionId") UUID questionId,
            @Param("type") SubmissionType type,
            @Param("status") SubmissionStatus status,
            Pageable pageable
    );

    /** Module 9 readiness: sessions in a contest that have a SUBMIT in the given status. */
    @Query("""
        SELECT DISTINCT s.sessionId FROM Submission s
        WHERE s.sessionId IN (SELECT a.id FROM AssessmentSession a WHERE a.contestId = :contestId)
          AND s.submissionType = :type
          AND s.status = :status
        """)
    List<UUID> findSessionIdsInContestWithStatus(
            @Param("contestId") UUID contestId,
            @Param("type") SubmissionType type,
            @Param("status") SubmissionStatus status
    );

    /** Module 9 detail view: SUBMIT attempts per question in a session, with their statuses. */
    @Query("""
        SELECT s.questionId, s.status, COUNT(s) FROM Submission s
        WHERE s.sessionId = :sessionId AND s.submissionType = :type
        GROUP BY s.questionId, s.status
        """)
    List<Object[]> countBySessionGroupedByQuestionAndStatus(
            @Param("sessionId") UUID sessionId,
            @Param("type") SubmissionType type
    );
}
