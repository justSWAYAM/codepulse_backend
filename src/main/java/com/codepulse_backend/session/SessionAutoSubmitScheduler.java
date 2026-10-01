package com.codepulse_backend.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionAutoSubmitScheduler {

    private final AssessmentSessionRepository sessionRepository;
    private final SessionService sessionService;
    private final Clock clock;

    @Scheduled(fixedDelay = 10_000)
    public void autoSubmitExpiredSessions() {
        Instant now = Instant.now(clock);

        var expiredSessions = sessionRepository
                .findAllByStatusAndEndsAtLessThanEqual(
                        SessionStatus.IN_PROGRESS,
                        now
                );

        for (AssessmentSession session : expiredSessions) {
            // One bad row must not stop the rest of the batch from being finalized
            try {
                sessionService.autoSubmitExpiredSession(session.getId());
            } catch (RuntimeException e) {
                log.error("Failed to auto-submit expired session [{}]", session.getId(), e);
            }
        }
    }
}