package com.codepulse_backend.testcase.repository;

import com.codepulse_backend.testcase.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {

    List<TestCase> findByQuestionIdOrderByOrderIndexAsc(UUID questionId);

    List<TestCase> findByQuestionIdAndIsSampleTrueOrderByOrderIndexAsc(
            UUID questionId
    );

    long countByQuestionId(UUID questionId);
}