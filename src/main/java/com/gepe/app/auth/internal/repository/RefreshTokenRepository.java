package com.gepe.app.auth.internal.repository;

import com.gepe.app.auth.internal.entity.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findByIdAndUserId(UUID id, UUID userId);

    /** Keyset (cursor) query — session aktif per user, terbaru dulu (AGENTS.md §4). */
    @Query("""
           select rt from RefreshToken rt
           where rt.userId = :userId
             and rt.status = com.gepe.app.auth.internal.entity.RefreshToken.Status.ACTIVE
             and rt.expiresAt > :now
             and (rt.issuedAt < :afterIssuedAt
                  or (rt.issuedAt = :afterIssuedAt and rt.id < :afterId))
           order by rt.issuedAt desc, rt.id desc
           """)
    List<RefreshToken> findActivePage(
            @Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("afterIssuedAt") Instant afterIssuedAt,
            @Param("afterId") UUID afterId,
            Pageable pageable);

    @Modifying
    @Query("update RefreshToken rt set rt.revokedAt = :now, "
           + "rt.status = com.gepe.app.auth.internal.entity.RefreshToken.Status.REVOKED "
           + "where rt.userId = :userId "
           + "and rt.status = com.gepe.app.auth.internal.entity.RefreshToken.Status.ACTIVE "
           + "and rt.id <> :excludeId")
    int revokeAllExcept(@Param("userId") UUID userId, @Param("excludeId") UUID excludeId,
                        @Param("now") Instant now);

    /** Cabut SEMUA sesi user (termasuk sesi saat ini) — dipakai saat role berubah (privilege change). */
    @Modifying
    @Query("update RefreshToken rt set rt.revokedAt = :now, "
           + "rt.status = com.gepe.app.auth.internal.entity.RefreshToken.Status.REVOKED "
           + "where rt.userId = :userId "
           + "and rt.status = com.gepe.app.auth.internal.entity.RefreshToken.Status.ACTIVE")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken rt set rt.revokedAt = :now, "
           + "rt.status = com.gepe.app.auth.internal.entity.RefreshToken.Status.REVOKED "
           + "where rt.sessionId = :sessionId "
           + "and rt.status = com.gepe.app.auth.internal.entity.RefreshToken.Status.ACTIVE")
    int revokeSessionFamily(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    List<RefreshToken> findBySessionId(UUID sessionId);

    @Modifying
    @Transactional // ini wajib disini, agar nanti kalo di loop di method dia ga ngonci lama misal 100rb row di konci. tapi cukup konci sebentar lalu buka dan konci lagi sampai loop selesai
    @Query(nativeQuery = true, value = """
        DELETE FROM auth.refresh_tokens
        WHERE id IN (
           SELECT id FROM auth.refresh_tokens
           WHERE (status = 'ACTIVE'  AND expires_at < :cutOff)
              OR (status = 'REVOKED' AND revoked_at < :revokedCutOff)
           ORDER BY id
           LIMIT :batchSize
        )
    """)
    int deleteExpiredBatch(@Param("cutOff") Instant cutOff, @Param("revokedCutOff") Instant revokedCutOff, @Param("batchSize") int batchSize);
}
