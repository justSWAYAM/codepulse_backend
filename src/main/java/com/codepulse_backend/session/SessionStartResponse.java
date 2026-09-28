package com.codepulse_backend.session;

import java.time.Instant;
import java.util.UUID;

public record SessionStartResponse(
        UUID sessionId,
        UUID contestId,
        SessionStatus status,
        Instant startedAt,
        Instant endsAt,
        Instant serverTime,
        long remainingSeconds,
        boolean resumed
) {}