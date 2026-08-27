package com.codepulse_backend.user.dto;

import com.codepulse_backend.common.enums.Role;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRequest(
        @NotNull(message = "Role is required")
        Role role,

        @NotNull(message = "Active status is required")
        Boolean isActive
) {}