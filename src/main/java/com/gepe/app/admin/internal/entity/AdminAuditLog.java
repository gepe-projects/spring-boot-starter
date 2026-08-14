package com.gepe.app.admin.internal.entity;

import com.gepe.app.platform.support.Uuidv7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Baris audit trail aksi admin (schema {@code admin}). Append-only — ditulis dalam
 * transaksi yang sama dengan mutasi yang diaudit (atomik lewat propagation REQUIRED).
 */
@Entity
@Table(name = "admin_audit_logs", schema = "admin")
@Getter
public class AdminAuditLog {

    @Id
    private UUID id;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    /** Payload JSON (string) — di-persist sebagai JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminAuditLog() {}

    public AdminAuditLog(UUID actorUserId, String action, String targetType,
                         String targetId, String payload) {
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.payload = payload;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = Uuidv7.generate();
        if (createdAt == null) createdAt = Instant.now();
    }
}
