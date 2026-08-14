package com.gepe.app.admin.internal.service;

import com.gepe.app.admin.internal.dto.AdminAuditLogDto;
import com.gepe.app.admin.internal.entity.AdminAuditLog;
import com.gepe.app.admin.internal.repository.AdminAuditLogRepository;
import com.gepe.app.platform.pagination.CursorBounds;
import com.gepe.app.platform.pagination.CursorPage;
import com.gepe.app.platform.pagination.CursorPages;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit trail aksi admin. {@code record(...)} dipanggil DALAM transaksi yang sama dengan
 * mutasi (REQUIRED propagation) sehingga audit log atomik dengan perubahan datanya.
 * Payload diserialisasi ke JSON (JSONB). Gagal serialize TIDAK menggagalkan operasi —
 * hanya dicatat sebagai warning (audit jangan memblokir admin action).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private static final int MAX_PAGE_SIZE = 50;

    private final AdminAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(UUID actorUserId, String action, String targetType, String targetId, Object payload) {
        String json = null;
        if (payload != null) {
            try {
                json = objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
                log.warn("Failed to serialize audit payload for action {}, skipping payload", action, e);
            }
        }
        repository.save(new AdminAuditLog(actorUserId, action, targetType, targetId, json));
    }

    @Transactional(readOnly = true)
    public CursorPage<AdminAuditLogDto> list(String cursor, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);

        CursorBounds<UUID> bounds = CursorBounds.resolve(cursor, UUID.class);
        List<AdminAuditLog> rows = repository.findPage(
                bounds.sortValue(), bounds.id(), CursorPages.pageable(pageSize));

        return CursorPages.page(rows, pageSize, AdminAuditLog::getCreatedAt, AdminAuditLog::getId, this::toDto);
    }

    private AdminAuditLogDto toDto(AdminAuditLog log) {
        return new AdminAuditLogDto(
                log.getId(),
                log.getActorUserId(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getPayload(),
                log.getCreatedAt());
    }
}
