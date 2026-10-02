package com.codepulse_backend.testcase.repository;

import com.codepulse_backend.testcase.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {

    List<TestCase> findByQuestionIdOrderByOrderIndexAsc(UUID questionId);

    List<TestCase> findByQuestionIdAndIsSampleTrueOrderByOrderIndexAsc(
            UUID questionId
    );

    long countByQuestionId(UUID questionId);

    /** MAX+1, not count+1: deletes leave gaps and count+1 would repeat an index. */
    @Query("SELECT COALESCE(MAX(t.orderIndex), 0) FROM TestCase t WHERE t.questionId = :questionId")
    int findMaxOrderIndexByQuestionId(@Param("questionId") UUID questionId);
}