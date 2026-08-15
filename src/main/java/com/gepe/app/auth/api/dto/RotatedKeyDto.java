package com.gepe.app.auth.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Hasil rotasi signing key — boundary DTO public (module lain, mis. admin,
 * menerimanya via {@link com.gepe.app.auth.api.KeyManagementService}). Status memakai
 * enum boundary {@link SigningKeyStatus}, konsisten dengan {@code UserDto.status}.
 */
public record RotatedKeyDto(
        UUID kid,
        SigningKeyStatus status,
        Instant notBefore) {
}
