package com.codepulse_backend.contest.repository;

import com.codepulse_backend.contest.entity.ContestCandidate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContestCandidateRepository extends JpaRepository<ContestCandidate, UUID> {

    boolean existsByContestIdAndCandidateId(UUID contestId, UUID candidateId);

    List<ContestCandidate> findAllByContestId(UUID contestId);

    long countByContestId(UUID contestId);

    void deleteAllByContestId(UUID contestId);

    Optional<ContestCandidate> findByContestIdAndCandidateId(
            UUID contestId,
            UUID candidateId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT cc
    FROM ContestCandidate cc
    WHERE cc.contest.id = :contestId
      AND cc.candidate.id = :candidateId
    """)
    Optional<ContestCandidate> findByContestIdAndCandidateIdForUpdate(
            @Param("contestId") UUID contestId,
            @Param("candidateId") UUID candidateId
    );
}