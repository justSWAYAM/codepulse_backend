package com.codepulse_backend.question.entity;

import com.codepulse_backend.common.entity.BaseEntity;
import com.codepulse_backend.common.enums.Difficulty;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.util.UUID;

@Entity
@Table(name = "questions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Question extends BaseEntity {

    /**
     * Stored as a plain UUID column (not @ManyToOne) to avoid bidirectional-relation
     * StackOverflowErrors and lazy-loading pitfalls. Service layer validates existence
     * via contestRepository.existsById() on write operations.
     */
    @Column(name = "contest_id", nullable = false)
    private UUID contestId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Difficulty difficulty;

    @Column(nullable = false)
    private int points;

    /**
     * Maximum execution time in milliseconds. Default 2000ms (2 seconds).
     */
    @Column(name = "time_limit_ms", nullable = false)
    private int timeLimitMs;

    /**
     * Maximum memory usage in kilobytes. Default 262144 KB = 256 MB.
     */
    @Column(name = "memory_limit_kb", nullable = false)
    private int memoryLimitKb;

    /**
     * 1-based ordering within the contest. Managed by QuestionService —
     * new questions append to the end, reorder endpoint reassigns values in bulk.
     */
    @Column(name = "order_index", nullable = false)
    private int orderIndex;
}
