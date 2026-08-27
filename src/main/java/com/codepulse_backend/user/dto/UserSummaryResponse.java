package com.codepulse_backend.user.dto;

import com.codepulse_backend.common.enums.Role;
import java.time.Instant;
import java.util.UUID;

public record UserSummaryResponse(
        UUID id,
        String email,
        String fullName,
        Role role,
        Boolean isActive,
        Instant createdAt
) {}