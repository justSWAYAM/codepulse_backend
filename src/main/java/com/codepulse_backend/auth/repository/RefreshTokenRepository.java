package com.codepulse_backend.auth.repository; // Updated package to match auth domain

import com.codepulse_backend.auth.entity.RefreshToken;
import com.codepulse_backend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Transactional
    @Modifying
    void deleteAllByUser(User user);

    // Alternatively, using nested property traversal (user.id):
    @Transactional
    @Modifying
    void deleteAllByUserId(UUID userId);

    @Transactional
    @Modifying
    int deleteAllByExpiresAtBefore(Instant now);
}