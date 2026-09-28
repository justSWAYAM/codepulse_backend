package com.codepulse_backend.session;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
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
            sessionService.autoSubmitExpiredSession(session.getId());
        }
    }
}