package com.codepulse_backend.result.repository;

import com.codepulse_backend.common.enums.ResultStatus;
import com.codepulse_backend.result.entity.Result;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResultRepository extends JpaRepository<Result, UUID> {

    Optional<Result> findByContestIdAndCandidateId(UUID contestId, UUID candidateId);

    List<Result> findAllByContestId(UUID contestId);

    long countByContestIdAndStatus(UUID contestId, ResultStatus status);

    /** "Ranked out of N". */
    long countByContestIdAndRankIsNotNull(UUID contestId);
}
