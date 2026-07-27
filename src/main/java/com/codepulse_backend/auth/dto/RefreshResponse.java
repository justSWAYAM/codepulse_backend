package com.codepulse_backend.auth.dto;

public record RefreshResponse(
        String accessToken,
        long expiresIn
) {}