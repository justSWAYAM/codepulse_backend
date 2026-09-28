package com.codepulse_backend.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class SessionFinalizedLoggingListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionFinalized(SessionFinalizedEvent event) {
        log.info(
                "Session [{}] finalized for contest [{}], candidate [{}], status [{}]",
                event.sessionId(),
                event.contestId(),
                event.candidateId(),
                event.finalStatus()
        );
    }
}