package com.gepe.app.auth.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Detail user untuk halaman admin — boundary record public.
 * Berisi semua field yang relevan untuk manajemen akun (status, roles, audit timestamp).
 */
public record AdminUserDetailDto(
        UUID userId,
        String email,
        boolean emailVerified,
        UserStatus status,
        Instant statusChangedAt,
        Instant createdAt,
        Instant updatedAt,
        List<String> roles) {
}
