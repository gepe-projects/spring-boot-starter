package com.gepe.app.admin.internal.service;

import com.gepe.app.admin.internal.dto.AdminAuditLogDto;
import com.gepe.app.admin.internal.entity.AdminAuditLog;
import com.gepe.app.admin.internal.repository.AdminAuditLogRepository;
import com.gepe.app.platform.exception.PlatformError;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.pagination.CursorEncoder;
import com.gepe.app.platform.pagination.CursorPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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
    private static final UUID MAX_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

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

        Instant afterCreatedAt;
        UUID afterId;
        if (cursor == null || cursor.isBlank()) {
            afterCreatedAt = Instant.now();
            afterId = MAX_UUID;
        } else {
            CursorEncoder.DecodedCursor decoded = CursorEncoder.decode(cursor);
            if (decoded == null || decoded.sortValues().length != 1) {
                throw new ServiceException(PlatformError.INVALID_CURSOR);
            }
            try {
                afterCreatedAt = Instant.ofEpochMilli(Long.parseLong(decoded.sortValues()[0]));
                afterId = UUID.fromString(decoded.id());
            } catch (RuntimeException e) {
                throw new ServiceException(PlatformError.INVALID_CURSOR);
            }
        }

        List<AdminAuditLog> rows = repository.findPage(
                afterCreatedAt, afterId, PageRequest.of(0, pageSize + 1));

        boolean hasMore = rows.size() > pageSize;
        List<AdminAuditLog> page = hasMore ? rows.subList(0, pageSize) : rows;

        List<AdminAuditLogDto> items = page.stream().map(this::toDto).toList();

        String nextCursor = null;
        if (hasMore) {
            AdminAuditLog last = page.get(page.size() - 1);
            nextCursor = CursorEncoder.encode(
                    last.getId().toString(),
                    String.valueOf(last.getCreatedAt().toEpochMilli()));
        }
        return new CursorPage<>(items, nextCursor, hasMore);
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
