package com.codepulse_backend.contest.repository;

import com.codepulse_backend.contest.entity.ContestCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContestCandidateRepository extends JpaRepository<ContestCandidate, UUID> {

    boolean existsByContestIdAndCandidateId(UUID contestId, UUID candidateId);

    List<ContestCandidate> findAllByContestId(UUID contestId);

    long countByContestId(UUID contestId);

    void deleteAllByContestId(UUID contestId);
}
