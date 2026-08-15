package com.gepe.app.user.api;

import com.gepe.app.user.api.dto.UserProfileDto;
import java.util.Optional;
import java.util.UUID;

/**
 * Kontrak sinkron module user (AGENTS.md §5 — prioritas 2: api interface).
 * Dipanggil module auth:
 * - {@code initialize} saat user BARU dibuat (registrasi/google login) — dalam transaksi yang sama,
 *   sehingga pembuatan profil atomik dengan pembuatan akun;
 * - {@code findByUserId} untuk agregasi GET /auth/me.
 */
public interface ProfileService {

    Optional<UserProfileDto> findByUserId(UUID userId);

    /**
     * Inisialisasi profil untuk user BARU. Idempotent: jika profil sudah ada, tidak melakukan apa-apa
     * dan TIDAK publish event {@code UserRegisteredEvent} (aman dipanggil ulang / retry).
     */
    void initialize(UUID userId, String email, String displayName, String avatarUrl);
}
