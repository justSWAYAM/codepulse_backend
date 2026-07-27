package com.codepulse_backend.auth.service;

import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.user.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    // HMAC secret must be at least 256 bits (32 bytes)
    private final String secretKey = "c3VwZXItc2VjcmV0LWtleS1mb3Itand0LXRlc3RpbmctcHVycG9zZXMtY29kZXB1bHNl";
    private final long accessTokenExpirationSeconds = 900; // 15 mins
    private final long refreshTokenExpirationSeconds = 604800; // 7 days

    private User mockUser;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();

        // Inject private fields matching exact field names in JwtService
        ReflectionTestUtils.setField(jwtService, "accessTokenSecret", secretKey);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpirySeconds", accessTokenExpirationSeconds);
        ReflectionTestUtils.setField(jwtService, "refreshTokenSecret", secretKey);
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpirySeconds", refreshTokenExpirationSeconds);

        mockUser = new User();
        mockUser.setId(UUID.randomUUID());
        mockUser.setEmail("test@codepulse.dev");
        mockUser.setRole(Role.ADMIN);
    }

    @Test
    @DisplayName("generateAccessToken() -> token is parseable and contains correct subject claim")
    void generateAccessToken_validUser_createsValidToken() {
        String token = jwtService.generateAccessToken(mockUser);

        assertNotNull(token);
        assertEquals("test@codepulse.dev", jwtService.extractEmail(token));
    }

    @Test
    @DisplayName("isAccessTokenValid() -> returns true for a fresh token")
    void isAccessTokenValid_freshToken_returnsTrue() {
        String token = jwtService.generateAccessToken(mockUser);
        assertTrue(jwtService.isAccessTokenValid(token));
    }

    @Test
    @DisplayName("isAccessTokenValid() -> returns false for a tampered token")
    void isAccessTokenValid_tamperedToken_returnsFalse() {
        String token = jwtService.generateAccessToken(mockUser);

        // Tamper with a character in the middle of the payload/signature to guarantee corruption
        int middleIndex = token.length() / 2;
        char targetChar = token.charAt(middleIndex);
        char replacementChar = (targetChar == 'A') ? 'B' : 'A';
        String tamperedToken = token.substring(0, middleIndex) + replacementChar + token.substring(middleIndex + 1);

        assertFalse(jwtService.isAccessTokenValid(tamperedToken));
    }

    @Test
    @DisplayName("isAccessTokenValid() -> returns false for an expired token")
    void isAccessTokenValid_expiredToken_returnsFalse() {
        JwtService expiredJwtService = new JwtService();
        ReflectionTestUtils.setField(expiredJwtService, "accessTokenSecret", secretKey);
        ReflectionTestUtils.setField(expiredJwtService, "accessTokenExpirySeconds", 0L);

        String expiredToken = expiredJwtService.generateAccessToken(mockUser);

        assertFalse(expiredJwtService.isAccessTokenValid(expiredToken));
    }

    @Test
    @DisplayName("hashRefreshToken() -> same input always produces same hash (deterministic SHA-256)")
    void hashRefreshToken_deterministicHash() {
        String rawToken = "raw-refresh-token-123456";

        String hash1 = jwtService.hashRefreshToken(rawToken);
        String hash2 = jwtService.hashRefreshToken(rawToken);

        assertNotNull(hash1);
        assertEquals(hash1, hash2);
    }
}