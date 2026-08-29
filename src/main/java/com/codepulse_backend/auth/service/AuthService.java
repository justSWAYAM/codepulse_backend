package com.codepulse_backend.auth.service;

import com.codepulse_backend.auth.dto.LoginRequest;
import com.codepulse_backend.auth.dto.LoginResponse;
import com.codepulse_backend.auth.dto.RefreshResponse;
import com.codepulse_backend.auth.entity.RefreshToken;
import com.codepulse_backend.auth.repository.RefreshTokenRepository;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.common.exception.UnauthorizedException;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.dto.UserSummary;
import com.codepulse_backend.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.WebUtils;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Value("${jwt.cookie.secure:false}")
    private boolean isCookieSecure;

    @Transactional
    public LoginResponse login(LoginRequest request, HttpServletResponse response) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + request.email()));

        if (!user.isActive()) {
            throw new DisabledException("User account is inactive");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = jwtService.generateRefreshToken(user);
        String tokenHash = jwtService.hashRefreshToken(rawRefreshToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusSeconds(jwtService.getRefreshTokenExpirySeconds()))
                .revoked(false)
                .build();

        refreshTokenRepository.save(refreshToken);
        setRefreshCookie(response, rawRefreshToken);

        UserSummary userSummary = UserSummary.from(user); // Align with your UserSummary constructor / mapper

        return new LoginResponse(
                accessToken,
                jwtService.getAccessTokenExpirySeconds(),
                userSummary
        );
    }

    @Transactional
    public RefreshResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawCookieToken = extractRefreshTokenFromCookie(request);
        if (rawCookieToken == null) {
            throw new UnauthorizedException("Refresh token cookie missing");
        }

        String tokenHash = jwtService.hashRefreshToken(rawCookieToken);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("Refresh token is expired or revoked");
        }

        // Token Rotation
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        User user = refreshToken.getUser();
        String newAccessToken = jwtService.generateAccessToken(user);
        String newRawRefreshToken = jwtService.generateRefreshToken(user);
        String newTokenHash = jwtService.hashRefreshToken(newRawRefreshToken);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(newTokenHash)
                .expiresAt(Instant.now().plusSeconds(jwtService.getRefreshTokenExpirySeconds()))
                .revoked(false)
                .build();

        refreshTokenRepository.save(newRefreshToken);
        setRefreshCookie(response, newRawRefreshToken);

        return new RefreshResponse(
                newAccessToken,
                jwtService.getAccessTokenExpirySeconds()
        );
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String rawCookieToken = extractRefreshTokenFromCookie(request);
        if (rawCookieToken != null) {
            String tokenHash = jwtService.hashRefreshToken(rawCookieToken);
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            });
        }
        clearRefreshCookie(response);
    }

    private String extractRefreshTokenFromCookie(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, "refresh_token");
        return cookie != null ? cookie.getValue() : null;
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", rawToken)
                .httpOnly(true)
                .secure(isCookieSecure)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(jwtService.getRefreshTokenExpirySeconds())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(isCookieSecure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}