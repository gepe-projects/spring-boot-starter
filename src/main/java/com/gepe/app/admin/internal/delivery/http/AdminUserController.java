package com.gepe.app.admin.internal.delivery.http;

import com.gepe.app.admin.internal.dto.AssignRolesRequest;
import com.gepe.app.admin.internal.dto.UpdateUserStatusRequest;
import com.gepe.app.admin.internal.service.AdminUserService;
import com.gepe.app.auth.api.AdminUserDetailDto;
import com.gepe.app.auth.api.UserDto;
import com.gepe.app.auth.api.UserStatus;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.pagination.CursorPage;
import com.gepe.app.platform.web.api.ApiVersions;
import com.gepe.app.platform.web.context.RequestContext;
import com.gepe.app.platform.web.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/" + ApiVersions.CURRENT + "/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
class AdminUserController {

    private final AdminUserService adminUserService;
    private final MessageHelper messageHelper;

    @GetMapping
    ResponseEntity<ApiResponse<CursorPage<UserDto>>> listUsers(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "status", required = false) UserStatus status) {
        return ResponseEntity.ok(new ApiResponse<>(null, adminUserService.listUsers(cursor, limit, status)));
    }

    @GetMapping("/{userId}")
    ResponseEntity<ApiResponse<AdminUserDetailDto>> getUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(new ApiResponse<>(null, adminUserService.getUser(userId)));
    }

    @PatchMapping("/{userId}/status")
    ResponseEntity<ApiResponse<Void>> updateStatus(@PathVariable UUID userId,
                                                   @Valid @RequestBody UpdateUserStatusRequest request) {
        adminUserService.changeStatus(RequestContext.getCurrentUserId(), userId, request.status());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("admin.user_status_updated"), null));
    }

    @PutMapping("/{userId}/roles")
    ResponseEntity<ApiResponse<Void>> assignRoles(@PathVariable UUID userId,
                                                  @Valid @RequestBody AssignRolesRequest request) {
        adminUserService.assignRoles(RequestContext.getCurrentUserId(), userId, request.roles());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("admin.roles_updated"), null));
    }
}
