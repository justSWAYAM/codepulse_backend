package com.codepulse_backend.result.entity;

import com.codepulse_backend.common.converter.StringListConverter;
import com.codepulse_backend.common.entity.BaseEntity;
import com.codepulse_backend.common.enums.ResultStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One row per assigned candidate per contest, recomputed whenever anything that
 * affects the score changes. Rank is NULL for ABSENT candidates.
 */
@Entity
@Table(
        name = "results",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_results_contest_candidate",
                columnNames = {"contest_id", "candidate_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class Result extends BaseEntity {

    @Column(name = "contest_id", nullable = false)
    private UUID contestId;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    /** NULL when the candidate never started (ABSENT). */
    @Column(name = "session_id")
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultStatus status;

    /** ReviewReason names. */
    @Convert(converter = StringListConverter.class)
    @Column(name = "review_reasons", columnDefinition = "TEXT")
    private List<String> reviewReasons = new ArrayList<>();

    @Column(name = "auto_score", nullable = false, precision = 10, scale = 2)
    private BigDecimal autoScore = BigDecimal.ZERO;

    @Column(name = "total_score", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalScore = BigDecimal.ZERO;

    @Column(name = "max_score", nullable = false, precision = 10, scale = 2)
    private BigDecimal maxScore = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean adjusted;

    @Column(name = "time_taken_seconds")
    private Long timeTakenSeconds;

    @Column(name = "rank")
    private Integer rank;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}
