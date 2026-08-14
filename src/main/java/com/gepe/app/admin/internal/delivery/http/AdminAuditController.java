package com.gepe.app.admin.internal.delivery.http;

import com.gepe.app.admin.internal.dto.AdminAuditLogDto;
import com.gepe.app.admin.internal.service.AdminAuditService;
import com.gepe.app.platform.pagination.CursorPage;
import com.gepe.app.platform.web.api.ApiVersions;
import com.gepe.app.platform.web.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/" + ApiVersions.CURRENT + "/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
class AdminAuditController {

    private final AdminAuditService auditService;

    @GetMapping
    ResponseEntity<ApiResponse<CursorPage<AdminAuditLogDto>>> listLogs(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(new ApiResponse<>(null, auditService.list(cursor, limit)));
    }
}
