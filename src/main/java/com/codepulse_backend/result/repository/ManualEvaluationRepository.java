package com.codepulse_backend.result.repository;

import com.codepulse_backend.result.entity.ManualEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ManualEvaluationRepository extends JpaRepository<ManualEvaluation, UUID> {

    /**
     * Full history for a session, newest first. The first row per question is the
     * active override (a NULL adjustedScore there means "reverted"). A session has
     * a handful of rows, so picking the newest in Java beats a database-specific query.
     */
    List<ManualEvaluation> findAllBySessionIdOrderByEvaluatedAtDescCreatedAtDesc(UUID sessionId);
}
