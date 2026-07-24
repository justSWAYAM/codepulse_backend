package com.codepulse_backend.common.audit;

import org.springframework.data.domain.AuditorAware;
import java.util.Optional;
import java.util.UUID;

public class AuditorAwareImpl implements AuditorAware<UUID> {
    @Override
    public Optional<UUID> getCurrentAuditor() {
        // TODO Module 1: pull authenticated user's UUID from SecurityContextHolder
        return Optional.empty();
    }
}