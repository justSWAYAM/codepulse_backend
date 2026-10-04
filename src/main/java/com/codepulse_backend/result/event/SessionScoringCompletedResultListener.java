package com.codepulse_backend.result.event;

import com.codepulse_backend.result.service.ResultService;
import com.codepulse_backend.submission.event.SessionScoringCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Recomputes a candidate's result once all their SUBMITs are judged (and after a rejudge). */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionScoringCompletedResultListener {

    private final ResultService resultService;

    @EventListener
    public void onScoringCompleted(SessionScoringCompletedEvent event) {
        try {
            resultService.recomputeCandidate(event.contestId(), event.candidateId());
        } catch (Exception e) {
            // Never propagate into the judge worker or the session finalizer.
            // Readiness shows the candidate as "missing"; Recompute fixes it.
            log.error("Failed to compute result for session {}", event.sessionId(), e);
        }
    }
}
