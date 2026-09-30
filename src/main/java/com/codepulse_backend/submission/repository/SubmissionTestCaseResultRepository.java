package com.codepulse_backend.submission.repository;

import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubmissionTestCaseResultRepository
        extends JpaRepository<SubmissionTestCaseResult, UUID> {

    List<SubmissionTestCaseResult> findBySubmissionId(UUID submissionId);

    void deleteBySubmissionId(UUID submissionId);


}