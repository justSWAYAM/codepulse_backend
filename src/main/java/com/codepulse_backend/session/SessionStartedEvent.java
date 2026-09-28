package com.codepulse_backend.session;

import java.util.UUID;

public record SessionStartedEvent(
        UUID sessionId,
        UUID contestId,
        UUID candidateId
) {}