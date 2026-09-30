package com.codepulse_backend.submission.service;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.common.exception.InvalidStateException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.entity.SubmissionTestCaseResult;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import com.codepulse_backend.submission.repository.SubmissionTestCaseResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SubmissionPersistenceService {

    private final SubmissionRepository submissionRepository;
    private final SubmissionTestCaseResultRepository resultRepository;


    public SubmissionPersistenceService(
            SubmissionRepository submissionRepository,
            SubmissionTestCaseResultRepository resultRepository
    ) {
        this.submissionRepository = submissionRepository;
        this.resultRepository = resultRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Submission createPendingSubmit(
            UUID sessionId,
            UUID questionId,
            UUID candidateId,
            String language,
            String sourceCode,
            int totalCount,
            Instant now
    ) {
        Submission submission = new Submission();

        submission.setSessionId(sessionId);
        submission.setQuestionId(questionId);
        submission.setCandidateId(candidateId);
        submission.setLanguage(language);
        submission.setSourceCode(sourceCode);
        submission.setSubmissionType(SubmissionType.SUBMIT);
        submission.setStatus(SubmissionStatus.PENDING);
        submission.setPassedCount(0);
        submission.setTotalCount(totalCount);
        submission.setSubmittedAt(now);
        submission.setQueuedAt(now);
        submission.setQueueAttempts(1);

        return submissionRepository.saveAndFlush(submission);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Submission saveCompletedRun(
            UUID sessionId,
            UUID questionId,
            UUID candidateId,
            String language,
            String sourceCode,
            SubmissionStatus status,
            int passedCount,
            int totalCount,
            String compileOutput,
            Instant submittedAt,
            List<SubmissionTestCaseResult> results
    ) {
        Submission submission = new Submission();

        submission.setSessionId(sessionId);
        submission.setQuestionId(questionId);
        submission.setCandidateId(candidateId);
        submission.setLanguage(language);
        submission.setSourceCode(sourceCode);
        submission.setSubmissionType(SubmissionType.RUN);
        submission.setStatus(status);
        submission.setPassedCount(passedCount);
        submission.setTotalCount(totalCount);
        submission.setCompileOutput(compileOutput);
        submission.setSubmittedAt(submittedAt);
        submission.setEvaluatedAt(Instant.now());

        Submission saved = submissionRepository.saveAndFlush(submission);

        for (SubmissionTestCaseResult result : results) {
            result.setSubmission(saved);
        }

        resultRepository.saveAll(results);

        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSystemError(UUID submissionId) {

        Submission submission = submissionRepository
                .findByIdForUpdate(submissionId)
                .orElse(null);

        if (submission == null) {
            return;
        }

        submission.setStatus(SubmissionStatus.SYSTEM_ERROR);
        submission.setEvaluatedAt(Instant.now());

        submissionRepository.save(submission);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyEvaluation(
            UUID submissionId,
            List<SubmissionTestCaseResult> results,
            BigDecimal score,
            SubmissionStatus status,
            int passedCount,
            int totalCount,
            String compileOutput,
            Instant evaluatedAt
    ) {
        Submission submission = submissionRepository.findByIdForUpdate(submissionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Submission not found: " + submissionId));

        if (submission.getStatus() != SubmissionStatus.PENDING) {
            return;
        }

        submission.setStatus(status);
        submission.setScore(score);
        submission.setPassedCount(passedCount);
        submission.setTotalCount(totalCount);
        submission.setCompileOutput(compileOutput);
        submission.setEvaluatedAt(evaluatedAt);

        submissionRepository.save(submission);

        resultRepository.deleteBySubmissionId(submissionId);

        for (SubmissionTestCaseResult result : results) {
            result.setSubmission(submission);
            resultRepository.save(result);
        }
    }
    @Transactional
    public Submission prepareForRejudge(UUID submissionId) {
        Submission submission = submissionRepository.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found"));

        if (submission.getSubmissionType() != SubmissionType.SUBMIT || submission.getStatus() == SubmissionStatus.PENDING) {
            throw new InvalidStateException("SUBMISSION_NOT_REJUDGEABLE");
        }

        // Wipe old results and reset state using the existing resultRepository[cite: 1]
        resultRepository.deleteBySubmissionId(submissionId);

        submission.setStatus(SubmissionStatus.PENDING);
        submission.setScore(null);
        submission.setQueueAttempts(1);
        submission.setQueuedAt(Instant.now());

        return submissionRepository.save(submission);
    }
}