package com.codepulse_backend.session;

import com.codepulse_backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "assessment_sessions",
        uniqueConstraints = @UniqueConstraint(
                name = "assessment_sessions_contest_id_candidate_id_key",
                columnNames = {"contest_id", "candidate_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class AssessmentSession extends BaseEntity {

    @Column(name = "contest_id", nullable = false)
    private UUID contestId;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SessionStatus status = SessionStatus.IN_PROGRESS;

    @Column(name = "submitted_at")
    private Instant submittedAt;
}