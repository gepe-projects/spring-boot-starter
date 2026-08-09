package com.gepe.app.auth.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionInfo(
        UUID sessionId,
        UUID refreshTokenId,
        String deviceInfo,
        String ipAddress,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        boolean isCurrent) {
}
