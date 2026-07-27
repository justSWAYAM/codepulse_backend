package com.codepulse_backend.auth.service;

import com.codepulse_backend.auth.dto.LoginRequest;
import com.codepulse_backend.auth.dto.LoginResponse;
import com.codepulse_backend.auth.dto.RefreshResponse;
import com.codepulse_backend.auth.entity.RefreshToken;
import com.codepulse_backend.auth.repository.RefreshTokenRepository;
import com.codepulse_backend.common.exception.AppException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private HttpServletResponse httpResponse;

    @InjectMocks
    private AuthService authService;

    private User mockUser;
    private RefreshToken mockRefreshToken;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "isCookieSecure", false);

        mockUser = new User();
        mockUser.setId(UUID.randomUUID());
        mockUser.setEmail("dev@codepulse.dev");
        mockUser.setPasswordHash("hashed_password");
        mockUser.setFullName("Dev User");
        mockUser.setActive(true);

        mockRefreshToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(mockUser)
                .tokenHash("hashed_raw_token")
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .build();

        lenient().when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Login with correct credentials -> returns LoginResponse with access token, sets cookie")
    void login_correctCredentials_returnsResponseAndSetsCookie() {
        LoginRequest request = new LoginRequest("dev@codepulse.dev", "Password@123");

        when(userRepository.findByEmail("dev@codepulse.dev")).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches("Password@123", "hashed_password")).thenReturn(true);
        when(jwtService.generateAccessToken(mockUser)).thenReturn("access_token_jwt");
        when(jwtService.generateRefreshToken(any(User.class))).thenReturn("raw_refresh_token");
        when(jwtService.hashRefreshToken(any())).thenReturn("hashed_raw_token");
        when(jwtService.getRefreshTokenExpirySeconds()).thenReturn(604800L);

        LoginResponse response = authService.login(request, httpResponse);

        assertNotNull(response);
        assertEquals("access_token_jwt", response.accessToken());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(httpResponse).addHeader(eq(HttpHeaders.SET_COOKIE), contains("refresh_token="));
    }

    @Test
    @DisplayName("Login with wrong password -> throws AppException (401 UNAUTHORIZED)")
    void login_wrongPassword_throwsUnauthorizedException() {
        LoginRequest request = new LoginRequest("dev@codepulse.dev", "WrongPassword");

        when(userRepository.findByEmail("dev@codepulse.dev")).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches("WrongPassword", "hashed_password")).thenReturn(false);

        assertThrows(AppException.class, () -> authService.login(request, httpResponse));
    }

    @Test
    @DisplayName("Login with unknown email -> throws ResourceNotFoundException")
    void login_unknownEmail_throwsResourceNotFoundException() {
        LoginRequest request = new LoginRequest("unknown@codepulse.dev", "Password@123");

        when(userRepository.findByEmail("unknown@codepulse.dev")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> authService.login(request, httpResponse));
    }

    @Test
    @DisplayName("Login with inactive account -> throws DisabledException")
    void login_inactiveAccount_throwsDisabledException() {
        mockUser.setActive(false);
        LoginRequest request = new LoginRequest("dev@codepulse.dev", "Password@123");

        when(userRepository.findByEmail("dev@codepulse.dev")).thenReturn(Optional.of(mockUser));

        assertThrows(DisabledException.class, () -> authService.login(request, httpResponse));
    }

    @Test
    @DisplayName("Refresh with valid token -> rotates token, returns new access token")
    void refresh_validToken_rotatesTokenAndReturnsResponse() {
        Cookie cookie = new Cookie("refresh_token", "raw_cookie_token");
        when(httpRequest.getCookies()).thenReturn(new Cookie[]{cookie});
        when(jwtService.hashRefreshToken("raw_cookie_token")).thenReturn("hashed_raw_token");
        when(refreshTokenRepository.findByTokenHash("hashed_raw_token")).thenReturn(Optional.of(mockRefreshToken));
        when(jwtService.generateAccessToken(mockUser)).thenReturn("new_access_token");
        when(jwtService.generateRefreshToken(any(User.class))).thenReturn("new_raw_refresh_token");
        when(jwtService.getRefreshTokenExpirySeconds()).thenReturn(604800L);

        RefreshResponse response = authService.refresh(httpRequest, httpResponse);

        assertNotNull(response);
        assertEquals("new_access_token", response.accessToken());
        assertTrue(mockRefreshToken.isRevoked());
        verify(httpResponse).addHeader(eq(HttpHeaders.SET_COOKIE), contains("refresh_token="));
    }

    @Test
    @DisplayName("Refresh with revoked token -> throws AppException")
    void refresh_revokedToken_throwsUnauthorizedException() {
        mockRefreshToken.setRevoked(true);
        Cookie cookie = new Cookie("refresh_token", "raw_cookie_token");

        when(httpRequest.getCookies()).thenReturn(new Cookie[]{cookie});
        when(jwtService.hashRefreshToken("raw_cookie_token")).thenReturn("hashed_raw_token");
        when(refreshTokenRepository.findByTokenHash("hashed_raw_token")).thenReturn(Optional.of(mockRefreshToken));

        assertThrows(AppException.class, () -> authService.refresh(httpRequest, httpResponse));
    }

    @Test
    @DisplayName("Refresh with expired token -> throws AppException")
    void refresh_expiredToken_throwsUnauthorizedException() {
        mockRefreshToken.setExpiresAt(Instant.now().minusSeconds(10));
        Cookie cookie = new Cookie("refresh_token", "raw_cookie_token");

        when(httpRequest.getCookies()).thenReturn(new Cookie[]{cookie});
        when(jwtService.hashRefreshToken("raw_cookie_token")).thenReturn("hashed_raw_token");
        when(refreshTokenRepository.findByTokenHash("hashed_raw_token")).thenReturn(Optional.of(mockRefreshToken));

        assertThrows(AppException.class, () -> authService.refresh(httpRequest, httpResponse));
    }

    @Test
    @DisplayName("Logout -> marks token revoked, clears cookie")
    void logout_validCookie_revokesTokenAndClearsCookie() {
        Cookie cookie = new Cookie("refresh_token", "raw_cookie_token");

        when(httpRequest.getCookies()).thenReturn(new Cookie[]{cookie});
        when(jwtService.hashRefreshToken("raw_cookie_token")).thenReturn("hashed_raw_token");
        when(refreshTokenRepository.findByTokenHash("hashed_raw_token")).thenReturn(Optional.of(mockRefreshToken));

        authService.logout(httpRequest, httpResponse);

        assertTrue(mockRefreshToken.isRevoked());
        verify(refreshTokenRepository).save(mockRefreshToken);
        verify(httpResponse).addHeader(eq(HttpHeaders.SET_COOKIE), contains("Max-Age=0"));
    }
}