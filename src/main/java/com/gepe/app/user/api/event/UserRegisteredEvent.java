package com.gepe.app.user.api.event;

import java.util.UUID;

/**
 * Dipublish module user saat profil user BARU dibuat (dipicu auth lewat {@code ProfileService.initialize}).
 * Konsumen: modul lain (mis. notification) via {@code @ApplicationModuleListener} — listener WAJIB idempotent
 * karena event bisa di-retry (AGENTS.md §5).
 *
 * <p>note: Spring Modulith hanya mempersist event yang punya minimal satu @ApplicationModuleListener —
 * publish tanpa listener adalah no-op, tidak nyangkut di tabel, tidak di-retry, tidak ada overhead.
 * ini di pake ntar misal kalo udah pake email, mau email greeting atau sebagainya
 */
public record UserRegisteredEvent(UUID userId, String email, String displayName, String avatarUrl) {}
