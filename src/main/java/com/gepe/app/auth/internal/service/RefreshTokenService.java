package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.dto.RotatedToken;
import com.gepe.app.auth.internal.dto.TokenWithId;
import com.gepe.app.auth.internal.entity.RefreshToken;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.repository.RefreshTokenRepository;
import com.gepe.app.platform.exception.GlobalError;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.support.Uuidv7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 64;

    private static final Duration RETENTION_AFTER_EXPIRY = Duration.ofDays(7);
    private static final Duration RETENTION_AFTER_REVOKED = Duration.ofDays(3);
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES_PER_RUN = 200;


    private final RefreshTokenRepository refreshTokenRepository;

    public TokenWithId issue(UUID userId, String deviceInfo, String ipAddress, Duration ttl) {
        String raw = generateRawToken();
        RefreshToken token = new RefreshToken();
        token.setId(Uuidv7.generate());
        token.setSessionId(token.getId());
        token.setUserId(userId);
        token.setTokenHash(sha256(raw));
        token.setDeviceInfo(deviceInfo);
        token.setIpAddress(ipAddress);
        token.setExpiresAt(Instant.now().plus(ttl));
        token.setIssuedAt(Instant.now());
        token.markUsed();
        refreshTokenRepository.save(token);
        return new TokenWithId(token.getId(), raw);
    }

    public RotatedToken rotate(String rawToken, String deviceInfo, String ipAddress) {
        String hash = sha256(rawToken);
        RefreshToken current = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ServiceException(AuthError.REFRESH_TOKEN_REVOKED));

        if (current.getStatus() == RefreshToken.Status.REVOKED) {
            log.warn("Refresh token reuse detected — revoking entire session: token_id={}, user_id={}, session_id={}",
                     current.getId(), current.getUserId(), current.getSessionId());
            refreshTokenRepository.revokeSessionFamily(current.getSessionId(), Instant.now());
            throw new ServiceException(AuthError.REFRESH_TOKEN_REVOKED);
        }

        if (current.isExpired()) {
            current.revoke();
            refreshTokenRepository.save(current);
            throw new ServiceException(AuthError.REFRESH_TOKEN_EXPIRED);
        }

        current.revoke();
        current.setRotatedAt(Instant.now());
        refreshTokenRepository.save(current);

        String newRaw = generateRawToken();
        String newHash = sha256(newRaw);
        Duration remainingTtl = Duration.between(Instant.now(), current.getExpiresAt());
        if (remainingTtl.isNegative()) {
            remainingTtl = Duration.ofMinutes(5);
        }

        RefreshToken rotated = new RefreshToken();
        rotated.setId(Uuidv7.generate());
        rotated.setSessionId(current.getSessionId());
        rotated.setUserId(current.getUserId());
        rotated.setTokenHash(newHash);
        rotated.setDeviceInfo(deviceInfo);
        rotated.setIpAddress(ipAddress);
        rotated.setParentTokenId(current.getId());
        rotated.setExpiresAt(Instant.now().plus(remainingTtl));
        rotated.setIssuedAt(Instant.now());
        rotated.markUsed();
        refreshTokenRepository.save(rotated);

        return new RotatedToken(rotated.getId(), newRaw, rotated.getUserId(), rotated.getSessionId());
    }

    public void revokeById(UUID tokenId) {
        refreshTokenRepository.findById(tokenId).ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);
        });
    }

    /** Dipakai logout — revoke berdasarkan raw token (hash-nya). */
    public void revokeByIdByHash(String rawToken) {
        refreshTokenRepository.findByTokenHash(sha256(rawToken)).ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);
        });
    }

    /** Resolve id dari raw token (untuk header X-Refresh-Token) — tanpa membeberkan id mentah. */
    public Optional<UUID> findIdByRawToken(String rawToken) {
        return refreshTokenRepository.findByTokenHash(sha256(rawToken)).map(RefreshToken::getId);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException(GlobalError.SYSTEM_ERROR);
        }
    }

    // rt cleeanup, jgn pake @transactional disini biar di handle repo aja karna ini looping ges biar ga ngelock lama
    public void cleanup(){
        Instant cutOff = Instant.now().minus(RETENTION_AFTER_EXPIRY);
        Instant cutOffRevoke = Instant.now().minus(RETENTION_AFTER_REVOKED);
        int deleted = 0;
        int batches = 0;
        int batch;
        do {
            batch = refreshTokenRepository.deleteExpiredBatch(cutOff,cutOffRevoke,BATCH_SIZE);
            deleted += batch;
            batches++;
            if (batch > 0) batches++;
        } while (batch > 0 && batches < MAX_BATCHES_PER_RUN);

        if (deleted > 0 ){
            log.info("Cleaned up expired refresh tokens: deleted={}, batches={}", deleted, batches);
        }
    }
}
