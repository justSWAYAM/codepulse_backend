package com.codepulse_backend.common.audit;

import com.codepulse_backend.common.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
public class AuditLog extends BaseEntity {

    private UUID actorId;
    private String action;
    private String entityType;
    private UUID  entityId;
    private String details;
}
