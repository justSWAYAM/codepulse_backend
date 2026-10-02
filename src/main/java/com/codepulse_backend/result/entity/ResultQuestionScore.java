package com.codepulse_backend.result.entity;

import com.codepulse_backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** How one question contributed to a result: which submission counted and whether it was overridden. */
@Entity
@Table(
        name = "result_question_scores",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_result_question",
                columnNames = {"result_id", "question_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class ResultQuestionScore extends BaseEntity {

    @Column(name = "result_id", nullable = false)
    private UUID resultId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    /** NULL when the candidate has no counted SUBMIT for this question. */
    @Column(name = "counted_submission_id")
    private UUID countedSubmissionId;

    /** The active override, if any. */
    @Column(name = "manual_evaluation_id")
    private UUID manualEvaluationId;

    @Column(name = "max_points", nullable = false)
    private int maxPoints;

    @Column(name = "auto_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal autoScore = BigDecimal.ZERO;

    @Column(name = "final_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal finalScore = BigDecimal.ZERO;

    @Column(name = "override_outdated", nullable = false)
    private boolean overrideOutdated;
}
