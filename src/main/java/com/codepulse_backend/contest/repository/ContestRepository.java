package com.codepulse_backend.contest.repository;

import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.contest.entity.Contest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ContestRepository extends JpaRepository<Contest, UUID> {

    // Admin/Evaluator: all contests filtered optionally by status
    Page<Contest> findAll(Pageable pageable);

    Page<Contest> findByStatus(ContestStatus status, Pageable pageable);

    // Candidate view: only contests they are assigned to
    @Query("""
        SELECT c FROM Contest c
        JOIN ContestCandidate cc ON cc.contest.id = c.id
        WHERE cc.candidate.id = :candidateId
        AND c.status IN :statuses
        """)
    Page<Contest> findAllByCandidateIdAndStatusIn(
            @Param("candidateId") UUID candidateId,
            @Param("statuses") List<ContestStatus> statuses,
            Pageable pageable
    );

    // Scheduler: PUBLISHED contests whose start_time has now passed
    List<Contest> findAllByStatusAndStartTimeBefore(ContestStatus status, Instant now);

    // Scheduler: ONGOING contests whose end_time has now passed
    List<Contest> findAllByStatusAndEndTimeBefore(ContestStatus status, Instant now);
}
