package com.codepulse_backend.submission.entity;

import com.codepulse_backend.common.entity.BaseEntity;
import com.codepulse_backend.common.enums.TestCaseResultStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "submission_test_case_results")
@Getter
@Setter
@NoArgsConstructor
public class SubmissionTestCaseResult extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @Column(name = "test_case_id", nullable = false)
    private UUID testCaseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TestCaseResultStatus status;

    @Column(name = "actual_output", columnDefinition = "TEXT")
    private String actualOutput;

    @Column(name = "stderr", columnDefinition = "TEXT")
    private String stderr;

    @Column(name = "execution_time_ms", precision = 10, scale = 2)
    private BigDecimal executionTimeMs;

    @Column(name = "memory_used_kb")
    private Integer memoryUsedKb;

    @Column(nullable = false)
    private int weight;

    @Column(name = "is_sample", nullable = false)
    private boolean isSample;
}