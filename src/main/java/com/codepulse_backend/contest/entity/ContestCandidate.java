package com.codepulse_backend.contest.entity;

import com.codepulse_backend.common.enums.ContestCandidateStatus;
import com.codepulse_backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contest_candidates",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_contest_candidate",
           columnNames = {"contest_id", "candidate_id"}
       ))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContestCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private User candidate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private ContestCandidateStatus status = ContestCandidateStatus.INVITED;

    @Column(name = "invited_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant invitedAt = Instant.now();
}
