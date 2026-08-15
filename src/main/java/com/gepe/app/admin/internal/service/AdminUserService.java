package com.gepe.app.admin.internal.service;

import com.gepe.app.auth.api.UserAdminService;
import com.gepe.app.auth.api.dto.AdminUserDetailDto;
import com.gepe.app.auth.api.dto.RoleType;
import com.gepe.app.auth.api.dto.UserDto;
import com.gepe.app.auth.api.dto.UserStatus;
import com.gepe.app.platform.web.pagination.CursorPage;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case admin untuk manajemen user. Orchestrasi: panggil kontrak {@link UserAdminService}
 * (module auth, satu transaksi) lalu tulis audit log di transaksi yang sama.
 * Guard domain (self-operation, last-admin) dipegang implementasi auth.api, bukan di sini.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserAdminService userAdminService;
    private final AdminAuditService auditService;

    @Transactional(readOnly = true)
    public CursorPage<UserDto> listUsers(String cursor, int limit, UserStatus status) {
        return userAdminService.listUsers(cursor, limit, status);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDto getUser(UUID userId) {
        return userAdminService.findUserDetail(userId);
    }

    @Transactional
    public void changeStatus(UUID actorId, UUID userId, UserStatus status) {
        userAdminService.changeStatus(actorId, userId, status);
        auditService.record(actorId, "USER_STATUS_CHANGED", "USER", userId.toString(),
                Map.of("status", status.name()));
    }

    @Transactional
    public void assignRoles(UUID actorId, UUID userId, Set<RoleType> roles) {
        userAdminService.assignRoles(actorId, userId, roles);
        auditService.record(actorId, "USER_ROLES_CHANGED", "USER", userId.toString(),
                Map.of("roles", roles.stream().map(Enum::name).toList()));
    }
}
