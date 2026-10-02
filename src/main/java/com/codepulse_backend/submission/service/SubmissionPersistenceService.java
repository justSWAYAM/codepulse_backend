package com.codepulse_backend.submission.service;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.common.exception.InvalidStateException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.session.AssessmentSessionRepository;
import com.codepulse_backend.session.SessionStatus;
import com.codepulse_backend.submission.event.SessionScoringCompletedEvent;
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
import java.util.Optional;
import java.util.UUID;

@Service
public class SubmissionPersistenceService {

    private final SubmissionRepository submissionRepository;
    private final SubmissionTestCaseResultRepository resultRepository;
    private final AssessmentSessionRepository sessionRepository;

    public SubmissionPersistenceService(
            SubmissionRepository submissionRepository,
            SubmissionTestCaseResultRepository resultRepository,
            AssessmentSessionRepository sessionRepository
    ) {
        this.submissionRepository = submissionRepository;
        this.resultRepository = resultRepository;
        this.sessionRepository = sessionRepository;
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
    /**
     * Idempotent: a non-PENDING row is left untouched.
     * Returns a SessionScoringCompletedEvent when this was the last pending SUBMIT
     * of a session that has already been finalized. The caller publishes it after
     * this transaction commits.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SessionScoringCompletedEvent> applyEvaluation(
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
            return Optional.empty();
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

        return scoringCompletedEvent(submission.getSessionId());
    }

    /** Marks a stale PENDING submit as re-queued. Returns false if it is no longer PENDING. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRequeued(UUID submissionId, Instant now) {
        Submission submission = submissionRepository.findByIdForUpdate(submissionId).orElse(null);

        if (submission == null || submission.getStatus() != SubmissionStatus.PENDING) {
            return false;
        }

        submission.setQueueAttempts(submission.getQueueAttempts() + 1);
        submission.setQueuedAt(now);
        submissionRepository.save(submission);
        return true;
    }

    /** Gives up on a PENDING submit (SYSTEM_ERROR, score null). Same completion rule as applyEvaluation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SessionScoringCompletedEvent> markAbandoned(UUID submissionId, Instant now) {
        Submission submission = submissionRepository.findByIdForUpdate(submissionId).orElse(null);

        if (submission == null || submission.getStatus() != SubmissionStatus.PENDING) {
            return Optional.empty();
        }

        submission.setStatus(SubmissionStatus.SYSTEM_ERROR);
        submission.setScore(null);
        submission.setEvaluatedAt(now);
        submissionRepository.save(submission);

        return scoringCompletedEvent(submission.getSessionId());
    }

    private Optional<SessionScoringCompletedEvent> scoringCompletedEvent(UUID sessionId) {
        submissionRepository.flush();

        return sessionRepository.findById(sessionId)
                .filter(session -> session.getStatus() != SessionStatus.IN_PROGRESS)
                .filter(session -> submissionRepository.countBySessionIdAndSubmissionTypeAndStatus(
                        sessionId,
                        SubmissionType.SUBMIT,
                        SubmissionStatus.PENDING
                ) == 0)
                .map(session -> new SessionScoringCompletedEvent(
                        session.getId(),
                        session.getContestId(),
                        session.getCandidateId()
                ));
    }
    @Transactional
    public Submission prepareForRejudge(UUID submissionId) {
        Submission submission = submissionRepository.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found"));

        if (submission.getSubmissionType() != SubmissionType.SUBMIT || submission.getStatus() == SubmissionStatus.PENDING) {
            throw new InvalidStateException("SUBMISSION_NOT_REJUDGEABLE");
        }

        // Wipe old results and reset state
        resultRepository.deleteBySubmissionId(submissionId);

        submission.setStatus(SubmissionStatus.PENDING);
        submission.setScore(null);
        submission.setQueueAttempts(1);
        submission.setQueuedAt(Instant.now());

        return submissionRepository.save(submission);
    }
}