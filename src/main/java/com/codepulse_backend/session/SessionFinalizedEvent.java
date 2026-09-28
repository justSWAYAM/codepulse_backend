package com.codepulse_backend.session;

import java.util.UUID;

public record SessionFinalizedEvent(
        UUID sessionId,
        UUID contestId,
        UUID candidateId,
        SessionStatus finalStatus
) {}