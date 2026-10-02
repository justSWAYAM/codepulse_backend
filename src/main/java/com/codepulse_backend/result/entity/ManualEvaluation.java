package com.codepulse_backend.result.entity;

import com.codepulse_backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only. The newest row per (session, question) is the active override;
 * a row with a NULL adjustedScore reverts the question to its automatic score.
 */
@Entity
@Table(name = "manual_evaluations")
@Getter
@Setter
@NoArgsConstructor
public class ManualEvaluation extends BaseEntity {

    /** The counted SUBMIT the evaluator reviewed. */
    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(name = "evaluator_id", nullable = false)
    private UUID evaluatorId;

    @Column(name = "adjusted_score", precision = 8, scale = 2)
    private BigDecimal adjustedScore;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String comments;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;
}
