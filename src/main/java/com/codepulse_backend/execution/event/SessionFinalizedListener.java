package com.codepulse_backend.execution.event;

import com.codepulse_backend.session.SessionFinalizedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SessionFinalizedListener {

    @EventListener
    public void handle(SessionFinalizedEvent event) {
        log.info(
                "Session finalized: sessionId={}, contestId={}, candidateId={}, finalStatus={}",
                event.sessionId(),
                event.contestId(),
                event.candidateId(),
                event.finalStatus()
        );
    }
}