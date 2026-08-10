package com.gepe.app.auth.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record RotatedKeyResponse(
        UUID kid,
        String status,
        Instant notBefore) {
}
