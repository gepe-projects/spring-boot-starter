package com.gepe.app.auth.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Detail user untuk halaman admin — boundary DTO public.
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
