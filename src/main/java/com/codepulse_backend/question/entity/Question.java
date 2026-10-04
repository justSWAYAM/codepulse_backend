package com.codepulse_backend.question.entity;

import com.codepulse_backend.common.entity.BaseEntity;
import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.QuestionType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.util.UUID;

@Entity
// Mirrors ux_questions_contest_order from V6 so the H2 test schema enforces it too.
// PostgreSQL does NOT enforce uniqueness across NULLs in multi-column unique indexes,
// so library questions (contest_id = NULL) are fine with this constraint in place.
@Table(name = "questions",
       uniqueConstraints = @UniqueConstraint(
           name = "ux_questions_contest_order",
           columnNames = {"contest_id", "order_index"}
       ))
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
     *
     * NULL = library question (Module 5A). Non-null = contest-owned question.
     */
    @Column(name = "contest_id")   // nullable — library questions have NULL here
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
     * For library questions (contest_id = NULL) this is set to 0 (unused).
     */
    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    // ─── Module 5A: Question Library additions ────────────────────────────────

    /**
     * Type discriminator. Defaults to DSA for backwards compatibility.
     * Used by Judge0 routing, test-case validation, and MCQ option presence.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 20)
    @Builder.Default
    private QuestionType questionType = QuestionType.DSA;

    /**
     * Subject folder this question belongs to.
     * NULL for contest-direct questions that were not authored in the library.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    /**
     * Points back to the library question this was deep-copied from.
     * NULL for original questions (library or direct contest creation).
     * ON DELETE SET NULL: deleting the library source never affects this copy.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_question_id")
    private Question sourceQuestion;

    // ─── SQL-specific ─────────────────────────────────────────────────────────

    /**
     * CREATE TABLE + seed INSERT statements executed before each test case.
     * Only meaningful when {@code questionType == SQL}.
     */
    @Column(name = "schema_sql", columnDefinition = "TEXT")
    private String schemaSql;

    /**
     * If true, the row order of the candidate's SQL result must match the
     * expected output exactly. If false, rows are compared as unordered sets.
     * Only meaningful when {@code questionType == SQL}.
     */
    @Column(name = "order_matters")
    private Boolean orderMatters;

    // ─── THEORY-specific ──────────────────────────────────────────────────────

    /**
     * Reference / model answer shown to evaluators during manual grading.
     * SECURITY: must NEVER appear in candidate-facing DTOs.
     * Only meaningful when {@code questionType == THEORY}.
     */
    @Column(name = "model_answer", columnDefinition = "TEXT")
    private String modelAnswer;
}

