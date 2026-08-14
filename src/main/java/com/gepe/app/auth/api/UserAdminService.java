package com.gepe.app.auth.api;

import com.gepe.app.platform.pagination.CursorPage;
import java.util.Set;
import java.util.UUID;

/**
 * Kontrak administratif module auth untuk modul lain (khususnya module {@code admin}).
 *
 * <p>Data user/role tetap dimiliki module auth (single writer). Modul lain TIDAK boleh
 * menyentuh repository/entity internal — hanya kontrak ini yang boleh dipanggil.
 * Setiap mutasi di sini menjaga invariant domain auth: cache {@code /me} ikut di-evict,
 * dan perubahan role mencabut seluruh sesi user (privilege change).
 */
public interface UserAdminService {

    /** List user dengan cursor pagination (keyset created_at DESC, id DESC), filter status opsional. */
    CursorPage<UserDto> listUsers(String cursor, int limit, UserStatus status);

    AdminUserDetailDto findUserDetail(UUID userId);

    /** Ganti status akun (ACTIVE/SUSPENDED/DISABLED). {@code actorId} = admin yang bertindak. */
    void changeStatus(UUID actorId, UUID userId, UserStatus status);

    /**
     * Replace penuh set role user. {@code actorId} = admin yang bertindak.
     * Efek samping: evict cache /me + revoke semua refresh token user (token berikutnya
     * membawa roles baru).
     */
    void assignRoles(UUID actorId, UUID userId, Set<RoleType> roles);
}
