package com.codepulse_backend.contest.event;

import java.util.UUID;

public record ContestCompletedEvent(UUID contestId) {
}