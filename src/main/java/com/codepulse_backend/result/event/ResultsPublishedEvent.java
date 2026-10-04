package com.codepulse_backend.result.event;

import java.util.UUID;

/** Published after results are published and committed. Module 11 pushes it to candidates. */
public record ResultsPublishedEvent(UUID contestId) {}
