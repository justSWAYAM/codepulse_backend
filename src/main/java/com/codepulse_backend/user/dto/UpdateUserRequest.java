package com.codepulse_backend.user.dto;

import com.codepulse_backend.common.enums.Role;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UpdateUserRequest(
        @NotNull(message = "Role is required")
        Role role,

        @NotNull(message = "Active status is required")
        Boolean isActive,

        String fullName,
        Integer year,
        
        @Pattern(regexp = "^(CSE|CE|ECS|MECH)$", message = "Branch must be CSE, CE, ECS, or MECH")
        String branch,
        
        @Pattern(regexp = "^(A|B|C)$", message = "Division must be A, B, or C")
        String division,
        
        @Pattern(regexp = "^(A|B|C|D)$", message = "Batch must be A, B, C, or D")
        String batch,
        
        @Pattern(regexp = "^\\d+$", message = "Roll number must be numeric")
        String rollNumber
) {}