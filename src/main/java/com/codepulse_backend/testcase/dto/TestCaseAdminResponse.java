package com.codepulse_backend.testcase.dto;

import java.util.UUID;

public record TestCaseAdminResponse(
        UUID id,
        String input,
        String expectedOutput,
        boolean isSample,
        int weight,
        int orderIndex
) {}