package com.codepulse_backend.testcase.dto;

import java.util.UUID;

public record TestCaseSampleResponse(
        UUID id,
        String input,
        int orderIndex
) {}