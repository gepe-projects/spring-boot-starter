package com.gepe.app.user.api.event;

import java.util.UUID;

/**
 * Dipublish oleh module user SETELAH profil berhasil di-update ({@code PATCH /users/me/profile}).
 *
 * <p>Konsumen: module auth via {@code @ApplicationModuleListener} — dipakai untuk evict cache
 * komposit GET /auth/me (UserDetailsDto). Listener WAJIB idempotent (evict cache aman
 * dipanggil ulang / retry). Dikirim AFTER_COMMIT, jadi cache lama masih bisa terbaca di
 * jendela kecil antara commit dan eksekusi listener — dibatasi TTL cache.
 */
public record ProfileUpdatedEvent(UUID userId) {}
