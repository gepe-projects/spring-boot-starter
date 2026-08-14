package com.gepe.app.auth.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gepe.app.auth.api.UserDto;
import com.gepe.app.user.api.UserProfileDto;

/**
 * Balikan GET /api/v1/auth/me: identitas lengkap ({@code UserDto} dari auth, termasuk emailVerified)
 * + profil (dari module user). Response DTO — komposisi dua boundary DTO publik, bukan entity.
 * Harus hidup di module auth (bukan user) karena mereferensikan {@code auth.api.UserDto};
 * menaruhnya di module user akan membuat cycle module (auth → user & user → auth).
 * profile = null saat user belum punya baris profil (mis. akun lama sebelum module user ada).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDetailsDto(UserDto user, UserProfileDto profile) {}
