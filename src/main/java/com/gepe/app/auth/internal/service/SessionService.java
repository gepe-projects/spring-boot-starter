package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.dto.SessionInfo;
import com.gepe.app.auth.internal.entity.RefreshToken;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.repository.RefreshTokenRepository;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.pagination.CursorBounds;
import com.gepe.app.platform.pagination.CursorPage;
import com.gepe.app.platform.pagination.CursorPages;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SessionService {

    private static final int MAX_PAGE_SIZE = 50;

    private final RefreshTokenRepository refreshTokenRepository;

    public CursorPage<SessionInfo> listActive(UUID userId, UUID currentTokenId, String cursor, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);

        Instant now = Instant.now();
        CursorBounds<UUID> bounds = CursorBounds.resolve(cursor, UUID.class);

        List<RefreshToken> rows = refreshTokenRepository.findActivePage(
                userId, now, bounds.sortValue(), bounds.id(), CursorPages.pageable(pageSize));

        return CursorPages.page(rows, pageSize, RefreshToken::getIssuedAt, RefreshToken::getId,
                rt -> toSessionInfo(rt, currentTokenId));
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

    private static SessionInfo toSessionInfo(RefreshToken rt, UUID currentTokenId) {
        return new SessionInfo(
                rt.getSessionId(),
                rt.getId(),
                rt.getDeviceInfo(),
                rt.getIpAddress(),
                rt.getIssuedAt(),
                rt.getLastUsedAt(),
                rt.getExpiresAt(),
                rt.getId().equals(currentTokenId));
    }
}
