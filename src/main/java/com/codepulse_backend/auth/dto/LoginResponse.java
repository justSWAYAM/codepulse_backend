package com.codepulse_backend.auth.dto;

import com.codepulse_backend.user.dto.UserSummary;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserSummary user
) {
    // Convenient constructor defaulting tokenType to "Bearer"
    public LoginResponse(String accessToken, long expiresIn, UserSummary user) {
        this(accessToken, "Bearer", expiresIn, user);
    }
}