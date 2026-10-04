package com.codepulse_backend.submission.event;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.common.enums.SubmissionType;
import com.codepulse_backend.session.SessionFinalizedEvent;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Replaces Module 7's SessionFinalizedListener stub.
 * If the finalized session has no PENDING submits left, scoring is complete now.
 * Otherwise SubmissionPersistenceService.applyEvaluation publishes the event
 * when the last pending submit finishes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionFinalizedHandler {

    private final SubmissionRepository submissionRepository;
    private final ApplicationEventPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSessionFinalized(SessionFinalizedEvent event) {
        long pending = submissionRepository.countBySessionIdAndSubmissionTypeAndStatus(
                event.sessionId(),
                SubmissionType.SUBMIT,
                SubmissionStatus.PENDING
        );

        if (pending > 0) {
            log.info("Session {} finalized with {} submission(s) still judging",
                    event.sessionId(), pending);
            return;
        }

        publisher.publishEvent(new SessionScoringCompletedEvent(
                event.sessionId(),
                event.contestId(),
                event.candidateId()
        ));
    }
}
