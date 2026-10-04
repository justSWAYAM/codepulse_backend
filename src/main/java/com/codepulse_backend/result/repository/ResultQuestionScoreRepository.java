package com.codepulse_backend.result.repository;

import com.codepulse_backend.result.entity.ResultQuestionScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ResultQuestionScoreRepository extends JpaRepository<ResultQuestionScore, UUID> {

    /** Leaderboard cells for a whole contest in one query. */
    List<ResultQuestionScore> findAllByResultIdIn(Collection<UUID> resultIds);

    List<ResultQuestionScore> findAllByResultId(UUID resultId);

    /** Recompute replaces a result's rows; bulk delete so the unique constraint can't trip on re-insert. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM ResultQuestionScore r WHERE r.resultId = :resultId")
    void deleteAllByResultId(@Param("resultId") UUID resultId);
}
