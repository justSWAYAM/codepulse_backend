package com.codepulse_backend.question.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One answer option for an MCQ question.
 * Multiple options can be correct (multi-select MCQ).
 * Order is controlled by {@code orderIndex}.
 *
 * SECURITY: the {@code isCorrect} field must NEVER appear in a
 * candidate-facing DTO — it is only included in admin/evaluator responses.
 */
@Entity
@Table(name = "mcq_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McqOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** FK to questions(id) — stored as plain UUID to avoid bidirectional cycle. */
    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    @Column(name = "is_correct", nullable = false)
    private boolean isCorrect;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
