package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.dto.SessionInfo;
import com.gepe.app.auth.internal.dto.SessionPage;
import com.gepe.app.auth.internal.entity.RefreshToken;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.repository.RefreshTokenRepository;
import com.gepe.app.platform.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SessionService {

    private final RefreshTokenRepository refreshTokenRepository;

    public SessionPage listActive(UUID userId, UUID currentTokenId, String cursor, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), 50);

        Instant now = Instant.now();
        Instant afterIssuedAt;
        UUID afterId;

        if (cursor == null || cursor.isBlank()) {
            afterIssuedAt = now.plus(Duration.ofDays(30));
            afterId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        } else {
            var decoded = decodeCursor(cursor);
            afterIssuedAt = decoded.issuedAt();
            afterId = decoded.id();
        }

        List<RefreshToken> rows = refreshTokenRepository.findActivePage(
                userId, now, afterIssuedAt, afterId, PageRequest.of(0, pageSize + 1));

        boolean hasMore = rows.size() > pageSize;
        List<RefreshToken> page = hasMore ? rows.subList(0, pageSize) : rows;

        List<SessionInfo> items = page.stream()
                .map(rt -> new SessionInfo(
                        rt.getSessionId(),
                        rt.getId(),
                        rt.getDeviceInfo(),
                        rt.getIpAddress(),
                        rt.getIssuedAt(),
                        rt.getLastUsedAt(),
                        rt.getExpiresAt(),
                        rt.getId().equals(currentTokenId)))
                .toList();

        String nextCursor = hasMore
                ? encodeCursor(page.get(page.size() - 1).getIssuedAt(), page.get(page.size() - 1).getId())
                : null;

        return new SessionPage(items, nextCursor);
    }

    public void revokeSession(UUID userId, UUID refreshTokenId, UUID currentTokenId) {
        if (refreshTokenId.equals(currentTokenId)) {
            throw new ServiceException(AuthError.CANNOT_REVOKE_CURRENT);
        }
        RefreshToken rt = refreshTokenRepository.findByIdAndUserId(refreshTokenId, userId)
                .orElseThrow(() -> new ServiceException(AuthError.SESSION_NOT_FOUND));
        rt.revoke();
        refreshTokenRepository.save(rt);
    }

    public int revokeAllExcept(UUID userId, UUID currentTokenId) {
        return refreshTokenRepository.revokeAllExcept(userId, currentTokenId, Instant.now());
    }

    private static String encodeCursor(Instant issuedAt, UUID id) {
        String raw = issuedAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record CursorPair(Instant issuedAt, UUID id) {}

    private static CursorPair decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor),
                                    StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 2);
            return new CursorPair(
                Instant.ofEpochMilli(Long.parseLong(parts[0])),
                UUID.fromString(parts[1])
            );
        } catch (Exception e) {
            throw new ServiceException(AuthError.CURRENT_TOKEN_REQUIRED);
        }
    }
}
