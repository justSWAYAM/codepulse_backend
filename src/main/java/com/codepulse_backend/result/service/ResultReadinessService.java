package com.codepulse_backend.result.service;

import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.ResultStatus;
import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.result.dto.ResultReadiness;
import com.codepulse_backend.result.entity.Result;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.session.AssessmentSessionRepository;
import com.codepulse_backend.session.SessionStatus;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds the publish checklist from roster, sessions and submissions (plan 2.9). Never stored. */
@Service
@RequiredArgsConstructor
public class ResultReadinessService {

    private final ContestCandidateRepository contestCandidateRepository;
    private final AssessmentSessionRepository sessionRepository;
    private final SubmissionRepository submissionRepository;

    public ResultReadiness build(Contest contest, List<Result> results) {
        UUID contestId = contest.getId();

        Set<UUID> assigned = contestCandidateRepository.findAllByContestId(contestId).stream()
                .map(cc -> cc.getCandidate().getId())
                .collect(Collectors.toSet());

        List<AssessmentSession> sessions = sessionRepository.findAllByContestId(contestId);
        Set<UUID> pendingSessions = new HashSet<>(submissionRepository.findSessionIdsInContestWithStatus(
                contestId, SubmissionType.SUBMIT, SubmissionStatus.PENDING));

        Set<UUID> sessionsWithResult = results.stream()
                .map(Result::getSessionId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> candidatesWithResult = results.stream()
                .map(Result::getCandidateId)
                .collect(Collectors.toSet());
        Set<UUID> candidatesWithSession = sessions.stream()
                .map(AssessmentSession::getCandidateId)
                .collect(Collectors.toSet());

        boolean completed = contest.getStatus() == ContestStatus.COMPLETED;

        int inProgress = 0;
        int judging = 0;
        int missing = 0;
        for (AssessmentSession session : sessions) {
            if (session.getStatus() == SessionStatus.IN_PROGRESS) {
                inProgress++;
            } else if (pendingSessions.contains(session.getId())) {
                judging++;
            } else if (!sessionsWithResult.contains(session.getId())) {
                missing++;
            }
        }
        if (completed) {
            // Never started: should have an ABSENT row once the contest is over
            missing += (int) assigned.stream()
                    .filter(id -> !candidatesWithSession.contains(id))
                    .filter(id -> !candidatesWithResult.contains(id))
                    .count();
        }

        int scored = count(results, ResultStatus.SCORED);
        int needsReview = count(results, ResultStatus.NEEDS_REVIEW);
        int absent = count(results, ResultStatus.ABSENT);
        boolean published = contest.isResultsPublished();

        return new ResultReadiness(
                assigned.size(),
                inProgress,
                judging,
                missing,
                scored,
                needsReview,
                absent,
                completed,
                published,
                completed && !published && inProgress == 0 && judging == 0 && missing == 0
        );
    }

    private static int count(List<Result> results, ResultStatus status) {
        return (int) results.stream().filter(r -> r.getStatus() == status).count();
    }
}
