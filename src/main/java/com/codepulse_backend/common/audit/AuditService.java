package com.codepulse_backend.common.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repository;

    public void log(UUID actorId, String action , String entityType, UUID entityId, String details) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setDetails(details);
        repository.save(entry);
    }
}
