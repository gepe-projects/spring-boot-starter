package com.gepe.app.auth.api.dto;

/**
 * Status akun user — boundary enum public (dipakai entity {@code auth.users.status},
 * {@code UserDto}, dan DTO request admin). Huruf besar semua, sinkron dengan
 * constraint {@code chk_users_status} di migration {@code V8__auth_user_status.sql}.
 *
 * <p>Hanya {@code ACTIVE} yang boleh autentikasi (login credentials / OAuth / refresh).
 * Nilai lain ditolak di {@code AuthService} dengan pesan berbeda per status.
 */
public enum UserStatus {
    /** Normal — boleh login. Default untuk akun baru dan backfill akun lama. */
    ACTIVE,
    /** Diblokir sementara oleh admin. Tidak boleh login. */
    SUSPENDED,
    /** Dinonaktifkan permanen. Tidak boleh login. */
    DISABLED
}
