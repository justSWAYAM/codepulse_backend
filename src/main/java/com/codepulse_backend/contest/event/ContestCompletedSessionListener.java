package com.codepulse_backend.contest.event;

import com.codepulse_backend.common.enums.ContestCandidateStatus;
import com.codepulse_backend.session.AssessmentSession;
import com.codepulse_backend.session.AssessmentSessionRepository;
import com.codepulse_backend.session.SessionService;
import com.codepulse_backend.session.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContestCompletedSessionListener {

    private final AssessmentSessionRepository sessionRepository;
    private final SessionService sessionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContestCompleted(ContestCompletedEvent event) {
        var activeSessions = sessionRepository
                .findAllByContestIdAndStatus(
                        event.contestId(),
                        SessionStatus.IN_PROGRESS
                );

        for (AssessmentSession session : activeSessions) {
            try {
                sessionService.finalizeSessionOnContestCompletion(session.getId());
            } catch (RuntimeException exception) {
                log.error(
                        "Failed to finalize session [{}] after contest [{}] completed",
                        session.getId(),
                        event.contestId(),
                        exception
                );
            }
        }
    }
}