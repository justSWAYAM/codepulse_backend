package com.codepulse_backend.question.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A subject folder that groups library questions.
 * Names are globally unique (case-insensitive enforced at service level
 * and by the DB UNIQUE constraint on the lowercase-stored name).
 *
 * Note: does NOT extend BaseEntity intentionally — subjects have no updatedAt
 * and no updatedBy; they are immutable after creation (rename is an Admin-only
 * rare operation modelled as delete + recreate to keep history clean).
 */
@Entity
@Table(name = "subjects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Subject name — unique, max 100 chars. */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** FK to users(id) — the evaluator/admin who created this subject folder. */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
