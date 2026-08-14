package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.UserDto;
import com.gepe.app.auth.api.UserStatus;
import com.gepe.app.auth.internal.dto.UserDetailsDto;
import com.gepe.app.platform.support.Uuidv7;
import com.gepe.app.user.api.Gender;
import com.gepe.app.user.api.UserProfileDto;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validasi round-trip JSON (Jackson 3) dari UserDetailsDto di Redis + kontrak get/put/evict.
 * Serialisasi memakai ObjectMapper yang sama dengan {@code UserDetailsCache} (Spring auto-config),
 * jadi ini juga mengunci bahwa record bersarang + Instant/LocalDate/enum bisa disimpan & dibaca ulang.
 */
@ExtendWith(MockitoExtension.class)
class UserDetailsCacheTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;

    final ObjectMapper objectMapper = new ObjectMapper();
    UserDetailsCache cache;

    @BeforeEach
    void setUp() {
        cache = new UserDetailsCache(redis, objectMapper, Duration.ofMinutes(10));
    }

    @Test
    void putThenGetRoundTripsFullDto() {
        when(redis.opsForValue()).thenReturn(valueOps);
        UUID userId = Uuidv7.generate();
        UserDetailsDto dto = new UserDetailsDto(
                new UserDto(userId, "a@b.com", true, UserStatus.ACTIVE, List.of("USER")),
                new UserProfileDto(
                        userId, "Nama", "nick", "https://example.com/a.png", "bio",
                        LocalDate.of(2000, 1, 1), Gender.OTHER, "0812", "Jakarta",
                        "Asia/Jakarta", "id",
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")));

        cache.put(userId, dto);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(key(userId)), json.capture(), eq(Duration.ofMinutes(10)));

        when(valueOps.get(key(userId))).thenReturn(json.getValue());
        Optional<UserDetailsDto> result = cache.get(userId);

        assertThat(result).hasValue(dto);
    }

    @Test
    void getReturnsEmptyWhenKeyMissing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        UUID userId = Uuidv7.generate();
        when(valueOps.get(key(userId))).thenReturn(null);

        assertThat(cache.get(userId)).isEmpty();
    }

    @Test
    void getDropsCorruptEntryAndReturnsEmpty() {
        when(redis.opsForValue()).thenReturn(valueOps);
        UUID userId = Uuidv7.generate();
        when(valueOps.get(key(userId))).thenReturn("{not-valid-json");

        assertThat(cache.get(userId)).isEmpty();
        verify(redis).delete(key(userId)); // entry korup dihapus, tidak macet
    }

    @Test
    void evictDeletesKey() {
        UUID userId = Uuidv7.generate();

        cache.evict(userId);

        verify(redis).delete(key(userId));
    }

    private String key(UUID userId) {
        return "cache:user-details:" + userId;
    }
}
