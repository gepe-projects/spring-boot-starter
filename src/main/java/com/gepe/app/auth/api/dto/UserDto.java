package com.gepe.app.auth.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Identitas auth user — boundary DTO public (dipakai {@code UserService},
 * {@code UserAdminService}, dan response login /auth/me). Snapshot data user + roles
 * efektif; status memakai enum boundary {@link UserStatus}.
 */
public record UserDto(UUID userId, String email, boolean emailVerified, UserStatus status, List<String> roles) {}
