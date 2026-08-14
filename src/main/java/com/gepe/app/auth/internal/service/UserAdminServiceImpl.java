package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.AdminUserDetailDto;
import com.gepe.app.auth.api.RoleType;
import com.gepe.app.auth.api.UserAdminService;
import com.gepe.app.auth.api.UserDto;
import com.gepe.app.auth.api.UserStatus;
import com.gepe.app.auth.internal.entity.Role;
import com.gepe.app.auth.internal.entity.User;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.repository.RefreshTokenRepository;
import com.gepe.app.auth.internal.repository.RoleRepository;
import com.gepe.app.auth.internal.repository.UserRepository;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.pagination.CursorBounds;
import com.gepe.app.platform.pagination.CursorPage;
import com.gepe.app.platform.pagination.CursorPages;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementasi kontrak admin module auth ({@link UserAdminService}).
 *
 * <p>Ini satu-satunya pintu mutasi akun untuk modul lain. Guard dijaga DI SINI
 * (bukan di controller), satu titik & unit-testable:
 * <ul>
 *   <li><b>Self</b> — akun biasa tidak bisa mengubah dirinya sendiri; pengecualian:
 *       akun {@code SUPER_ADMIN} boleh mengubah akunnya sendiri (akun root protected).</li>
 *   <li><b>Hierarki</b> — actor hanya bisa mengubah akun dengan rank di bawahnya
 *       (SUPER_ADMIN &gt; ADMIN &gt; OPERATION &gt; USER). Artinya ADMIN tidak bisa
 *       mengubah ADMIN lain, dan tidak ada siapa pun yang bisa mengubah SUPER_ADMIN
 *       selain akun itu sendiri.</li>
 *   <li><b>Grant</b> — tidak bisa memberi role dengan rank ≥ rank sendiri; hanya
 *       SUPER_ADMIN yang bisa memberi role SUPER_ADMIN (membuat peer baru).</li>
 *   <li><b>Anti-lockout</b> — tidak bisa menonaktifkan/menangguhkan SUPER_ADMIN aktif
 *       terakhir, dan tidak bisa mencabut role SUPER_ADMIN dari pemegang terakhir.</li>
 * </ul>
 * Setiap mutasi men-evict cache {@code /me} (Redis); perubahan role juga mencabut
 * seluruh sesi refresh token user supaya JWT berikutnya membawa roles baru.
 */
@Service
@RequiredArgsConstructor
public class UserAdminServiceImpl implements UserAdminService {

    private static final int MAX_PAGE_SIZE = 50;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserDetailsCache userDetailsCache;

    @Override
    @Transactional(readOnly = true)
    public CursorPage<UserDto> listUsers(String cursor, int limit, UserStatus status) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);

        CursorBounds<UUID> bounds = CursorBounds.resolve(cursor, UUID.class);
        List<User> rows = status != null
                ? userRepository.findAdminPageByStatus(status, bounds.sortValue(), bounds.id(), CursorPages.pageable(pageSize))
                : userRepository.findAdminPage(bounds.sortValue(), bounds.id(), CursorPages.pageable(pageSize));

        return CursorPages.page(rows, pageSize, User::getCreatedAt, User::getId, this::toUserDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserDetailDto findUserDetail(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ServiceException(AuthError.USER_NOT_FOUND));
        return new AdminUserDetailDto(
                user.getId(),
                user.getEmail(),
                user.getEmailVerifiedAt() != null,
                user.getStatus(),
                user.getStatusChangedAt(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                RoleResolver.effectiveRoles(user));
    }

    @Override
    @Transactional
    public void changeStatus(UUID actorId, UUID userId, UserStatus newStatus) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ServiceException(AuthError.USER_NOT_FOUND));
        resolveActor(actorId, target); // guard self (non-SUPER_ADMIN) + hierarki

        if (hasRole(target, RoleType.SUPER_ADMIN) && newStatus != UserStatus.ACTIVE
                && userRepository.countUsersWithRoleAndStatus(RoleType.SUPER_ADMIN.name(), UserStatus.ACTIVE) <= 1) {
            throw new ServiceException(AuthError.LAST_SUPER_ADMIN_STATUS);
        }

        target.changeStatus(newStatus);
        userRepository.save(target);
        userDetailsCache.evict(userId);
    }

    @Override
    @Transactional
    public void assignRoles(UUID actorId, UUID userId, Set<RoleType> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new ServiceException(AuthError.ROLE_SET_EMPTY);
        }

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ServiceException(AuthError.USER_NOT_FOUND));
        User actor = resolveActor(actorId, target); // guard self (non-SUPER_ADMIN) + hierarki
        assertCanGrant(actor, roles);

        Set<Role> roleEntities = roles.stream()
                .map(roleType -> roleRepository.findById(roleType.name())
                        .orElseThrow(() -> new ServiceException(AuthError.ROLE_NOT_FOUND, roleType.name())))
                .collect(Collectors.toSet());

        if (hasRole(target, RoleType.SUPER_ADMIN) && !roles.contains(RoleType.SUPER_ADMIN)
                && userRepository.countUsersWithRole(RoleType.SUPER_ADMIN.name()) <= 1) {
            throw new ServiceException(AuthError.LAST_SUPER_ADMIN_REMOVAL);
        }
        if (hasRole(target, RoleType.ADMIN) && !roles.contains(RoleType.ADMIN)
                && userRepository.countUsersWithRole(RoleType.ADMIN.name()) <= 1) {
            throw new ServiceException(AuthError.LAST_ADMIN_REMOVAL);
        }

        target.replaceRoles(roleEntities);
        userRepository.save(target);
        userDetailsCache.evict(userId);
        // Privilege change: cabut semua sesi user → token berikutnya (refresh/login)
        // membawa roles baru. Access token lama tetap valid sampai expiry (≤15m),
        // konsisten dengan kebijakan no-deny-list AT yang sudah ada.
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }

    /**
     * Validasi "siapa boleh mengubah siapa". Mengembalikan actor (dibutuhkan untuk
     * guard grant pada {@code assignRoles}).
     *
     * <p>Aturan: akun biasa tidak boleh mengubah dirinya sendiri; SUPER_ADMIN boleh
     * mengubah dirinya sendiri; selain itu actor harus punya rank lebih tinggi dari
     * target (rank: SUPER_ADMIN &gt; ADMIN &gt; OPERATION &gt; USER).
     */
    private User resolveActor(UUID actorId, User target) {
        if (actorId != null && actorId.equals(target.getId())) {
            if (hasRole(target, RoleType.SUPER_ADMIN)) {
                return target; // akun root protected — hanya dirinya sendiri yang boleh mengubahnya
            }
            throw new ServiceException(AuthError.SELF_OPERATION_FORBIDDEN);
        }
        if (actorId == null) {
            throw new ServiceException(AuthError.INSUFFICIENT_PRIVILEGE);
        }
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ServiceException(AuthError.USER_NOT_FOUND));
        if (maxRank(actor.getRoles()) <= maxRank(target.getRoles())) {
            throw new ServiceException(AuthError.INSUFFICIENT_PRIVILEGE);
        }
        return actor;
    }

    /** Tidak boleh memberi role dengan rank ≥ rank actor; hanya SUPER_ADMIN yang boleh memberi SUPER_ADMIN. */
    private void assertCanGrant(User actor, Set<RoleType> roles) {
        int actorRank = maxRank(actor.getRoles());
        for (RoleType role : roles) {
            int roleRank = rank(role);
            if (roleRank >= actorRank
                    && !(role == RoleType.SUPER_ADMIN && actorRank == rank(RoleType.SUPER_ADMIN))) {
                throw new ServiceException(AuthError.INSUFFICIENT_PRIVILEGE);
            }
        }
    }

    private UserDto toUserDto(User user) {
        return new UserDto(user.getId(), user.getEmail(),
                user.getEmailVerifiedAt() != null, user.getStatus(), RoleResolver.effectiveRoles(user));
    }

    private static boolean hasRole(User user, RoleType role) {
        return user.getRoles().stream().anyMatch(r -> r.getName().equals(role.name()));
    }

    private static int maxRank(Set<Role> roles) {
        return roles.stream()
                .map(Role::getName)
                .mapToInt(UserAdminServiceImpl::rankOfName)
                .max()
                .orElse(0);
    }

    private static int rankOfName(String name) {
        try {
            return rank(RoleType.valueOf(name));
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static int rank(RoleType role) {
        return switch (role) {
            case SUPER_ADMIN -> 3;
            case ADMIN -> 2;
            case OPERATION -> 1;
            case USER -> 0;
        };
    }
}
