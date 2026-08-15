package com.gepe.app.auth.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Info signing key untuk listing admin — boundary DTO public. Status memakai enum
 * boundary {@link SigningKeyStatus} (ACTIVE|PREVIOUS|RETIRED), bukan String mentah —
 * konsisten dengan {@code UserDto.status}.
 */
public record SigningKeyDto(
        UUID kid,
        String algorithm,
        SigningKeyStatus status,
        Instant notBefore,
        Instant notAfter,
        Instant createdAt) {
}
