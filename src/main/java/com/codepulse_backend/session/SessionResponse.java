package com.codepulse_backend.session;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID sessionId,
        UUID contestId,
        SessionStatus status,
        Instant startedAt,
        Instant endsAt,
        Instant submittedAt,
        Instant serverTime,
        long remainingSeconds
) {}