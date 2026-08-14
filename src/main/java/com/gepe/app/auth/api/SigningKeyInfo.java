package com.gepe.app.auth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Info signing key untuk listing admin — boundary record public.
 * Status memakai String (ACTIVE|PREVIOUS|RETIRED) supaya bebas dari enum entity internal.
 */
public record SigningKeyInfo(
        UUID kid,
        String algorithm,
        String status,
        Instant notBefore,
        Instant notAfter,
        Instant createdAt) {
}
