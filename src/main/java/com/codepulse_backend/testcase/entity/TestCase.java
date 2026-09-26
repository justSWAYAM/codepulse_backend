
package com.codepulse_backend.testcase.entity;

import com.codepulse_backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.util.UUID;

@Entity
@Table(name = "test_cases")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCase extends BaseEntity {

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String input;

    @Column(
            name = "expected_output",
            columnDefinition = "TEXT",
            nullable = false
    )
    private String expectedOutput;

    @Column(name = "is_sample", nullable = false)
    private boolean isSample;

    @Column(nullable = false)
    private int weight;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;
}