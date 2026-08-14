package com.gepe.app.admin.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminAuditLogDto(
        UUID id,
        UUID actorUserId,
        String action,
        String targetType,
        String targetId,
        String payload,
        Instant createdAt) {
}
