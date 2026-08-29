package com.codepulse_backend.auth.service;

import com.codepulse_backend.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;

@Service
public class JwtService {

    @Value("${jwt.access-token.secret}")
    private String accessTokenSecret;

    @Getter
    @Value("${jwt.access-token.expiry-seconds}")
    private long accessTokenExpirySeconds;

    @Value("${jwt.refresh-token.secret}")
    private String refreshTokenSecret;

    @Getter
    @Value("${jwt.refresh-token.expiry-seconds}")
    private long refreshTokenExpirySeconds;

    public String generateAccessToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole().name());
        claims.put("userId", user.getId().toString());
        claims.put("fullName", user.getFullName());

        long now = System.currentTimeMillis();

        return Jwts.builder()
                .claims(claims)
                .subject(user.getEmail())
                .issuedAt(new Date(now))
                .expiration(new Date(now + (accessTokenExpirySeconds * 1000)))
                .signWith(getSecretKey(accessTokenSecret))
                .compact();
    }

    public String generateRefreshToken(User user) {
        long now = System.currentTimeMillis();

        return Jwts.builder()
                .subject(user.getEmail())
                .id(java.util.UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + (refreshTokenExpirySeconds * 1000)))
                .signWith(getSecretKey(refreshTokenSecret))
                .compact();
    }

    public String extractEmail(String token) {
        return extractAllClaims(token, accessTokenSecret).getSubject();
    }

    public boolean isAccessTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token, accessTokenSecret);
            return claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            // Handles ExpiredJwtException, SignatureException, MalformedJwtException, etc.
            return false;
        }
    }

    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private Claims extractAllClaims(String token, String secret) {
        return Jwts.parser()
                .verifyWith(getSecretKey(secret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSecretKey(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}