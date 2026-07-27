package com.codepulse_backend.user.dto;

import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.user.User;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String fullName,
        Role role,
        String rollNumber
) {
    public static UserSummary from(User user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getRollNumber()
        );
    }
}