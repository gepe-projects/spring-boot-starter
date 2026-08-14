package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.dto.UserDetailsDto;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Cache Redis untuk komposit GET /api/v1/auth/me ({@code UserDetailsDto} = UserDto + profile).
 *
 * <p>PUBLIC hanya karena dikonsumsi lintas sub-package dalam module yang sama
 * ({@code AuthService} di service/, {@code ProfileUpdateCacheEvictor} di listener/) —
 * ini tetap kelas internal module auth, BUKAN kontrak untuk module lain (jangan dipakai
 * dari luar package {@code com.gepe.app.auth.internal}).
 *
 * <p>Kenapa Redis, bukan in-memory: AGENTS.md §6 — state cache lintas instance WAJIB shared
 * (multi-instance safe). TTL dipakai sebagai safety net; evict eksplisit dilakukan di semua
 * jalur mutasi (profil, status akun, verifikasi email) supaya data tidak basi lebih lama
 * dari yang perlu.
 *
 * <p>Gagal serialize/deserialize TIDAK pernah menggagalkan request — dianggap cache miss
 * (fallback ke DB), dan entry yang korup dihapus supaya tidak macet.
 */
@Slf4j
@Component
public class UserDetailsCache {

    private static final String PREFIX = "cache:user-details:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    UserDetailsCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${app.security.user-details-cache-ttl:10m}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public Optional<UserDetailsDto> get(UUID userId) {
        String raw = redis.opsForValue().get(key(userId));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(raw, UserDetailsDto.class));
        } catch (JacksonException e) {
            log.warn("Failed to deserialize cached user details for {}, dropping entry", userId, e);
            redis.delete(key(userId));
            return Optional.empty();
        }
    }

    public void put(UUID userId, UserDetailsDto dto) {
        try {
            redis.opsForValue().set(key(userId), objectMapper.writeValueAsString(dto), ttl);
        } catch (JacksonException e) {
            log.warn("Failed to serialize user details for {}, skipping cache", userId, e);
        }
    }

    public void evict(UUID userId) {
        redis.delete(key(userId));
    }

    private String key(UUID userId) {
        return PREFIX + userId;
    }
}
