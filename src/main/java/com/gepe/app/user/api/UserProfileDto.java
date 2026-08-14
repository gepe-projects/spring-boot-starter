package com.gepe.app.user.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Profil user — DTO boundary module user.
 * TIDAK berisi data identitas auth (email/roles) — itu milik {@code auth.api.UserDto}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfileDto(
        UUID userId,
        String displayName,
        String nickname,
        String avatarUrl,
        String bio,
        LocalDate dateOfBirth,
        Gender gender,
        String phone,
        String location,
        String timezone,
        String locale,
        Instant createdAt,
        Instant updatedAt) {}
