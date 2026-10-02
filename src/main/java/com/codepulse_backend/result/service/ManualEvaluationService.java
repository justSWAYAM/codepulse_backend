package com.codepulse_backend.result.service;

import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.common.exception.BadRequestException;
import com.codepulse_backend.common.exception.ConflictException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.question.entity.Question;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.result.ResultAuditAction;
import com.codepulse_backend.result.dto.ManualEvaluationRequest;
import com.codepulse_backend.result.dto.ResultResponse;
import com.codepulse_backend.result.entity.ManualEvaluation;
import com.codepulse_backend.result.repository.ManualEvaluationRepository;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.session.AssessmentSessionRepository;
import com.codepulse_backend.session.SessionStatus;
import com.codepulse_backend.submission.entity.Submission;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evaluator overrides (plan 2.6): an override sets the candidate's final score for a
 * question and is anchored to the counted SUBMIT. Append-only; a NULL score reverts.
 */
@Service
@RequiredArgsConstructor
public class ManualEvaluationService {

    private final SubmissionRepository submissionRepository;
    private final AssessmentSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final ManualEvaluationRepository evaluationRepository;
    private final ResultService resultService;
    private final AuditService auditService;

    @Transactional
    public ResultResponse evaluate(UUID submissionId, ManualEvaluationRequest request, UUID evaluatorId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("SUBMISSION_NOT_FOUND"));
        AssessmentSession session = sessionRepository.findById(submission.getSessionId())
                .orElseThrow(() -> new ResourceNotFoundException("SUBMISSION_NOT_FOUND"));

        // Lock first, so publish can't slip in between these checks and the insert
        Contest contest = resultService.lockContest(session.getContestId());

        if (contest.isResultsPublished()) {
            throw new ConflictException("RESULTS_PUBLISHED_LOCKED",
                    "Results are published. Unpublish them to change scores");
        }
        if (submission.getSubmissionType() != SubmissionType.SUBMIT
                || submission.getStatus() == SubmissionStatus.PENDING
                || submission.getStatus() == SubmissionStatus.SYSTEM_ERROR
                || session.getStatus() == SessionStatus.IN_PROGRESS) {
            throw new ConflictException("SUBMISSION_NOT_EVALUABLE",
                    "Only finished SUBMIT submissions from an ended exam can be scored by hand");
        }
        boolean counted = submissionRepository.findCountedSubmissions(session.getId()).stream()
                .anyMatch(s -> s.getId().equals(submission.getId()));
        if (!counted) {
            throw new ConflictException("SUBMISSION_NOT_COUNTED",
                    "This isn't the submission that counts for this question");
        }

        Question question = questionRepository.findById(submission.getQuestionId())
                .orElseThrow(() -> new ResourceNotFoundException("Question not found"));

        BigDecimal adjusted = request.adjustedScore();
        if (adjusted != null
                && (adjusted.stripTrailingZeros().scale() > 2
                    || adjusted.compareTo(BigDecimal.valueOf(question.getPoints())) > 0)) {
            throw new BadRequestException("ADJUSTED_SCORE_OUT_OF_RANGE");
        }

        ManualEvaluation evaluation = new ManualEvaluation();
        evaluation.setSubmissionId(submission.getId());
        evaluation.setSessionId(session.getId());
        evaluation.setQuestionId(question.getId());
        evaluation.setEvaluatorId(evaluatorId);
        evaluation.setAdjustedScore(adjusted);
        evaluation.setComments(request.comments().trim());
        evaluation.setEvaluatedAt(Instant.now());
        evaluationRepository.saveAndFlush(evaluation);

        auditService.log(
                evaluatorId,
                ResultAuditAction.SUBMISSION_EVALUATED,
                "SUBMISSION",
                submission.getId(),
                "questionId=" + question.getId()
                        + ", candidateId=" + submission.getCandidateId()
                        + (adjusted == null
                                ? ", reverted to automatic score " + submission.getScore()
                                : ", " + submission.getScore() + " -> " + adjusted)
        );

        resultService.recomputeCandidateLocked(contest, submission.getCandidateId());
        resultService.rerank(contest.getId());

        return resultService.getCandidateResult(contest.getId(), submission.getCandidateId());
    }
}
