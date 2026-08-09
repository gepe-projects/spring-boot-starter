package com.gepe.app.auth.internal.service;

import com.gepe.app.platform.exception.GlobalError;
import com.gepe.app.platform.exception.ServiceException;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
class LoginRateLimiter {

    private static final String KEY_PREFIX = "auth:login:";

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    LoginRateLimiter(StringRedisTemplate stringRedisTemplate, RateLimitProperties rateLimitProperties) {
        this.redis = stringRedisTemplate;
        this.props = rateLimitProperties;
    }

    void assertAllowed(String email) {
        String key = key(email);
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return;
        }
        LockState state = parse(raw);
        if (state == null) {
            return;
        }
        Instant lockedUntil = Instant.ofEpochMilli(state.lockedUntilEpoch());
        if (Instant.now().isBefore(lockedUntil)) {
            throw new ServiceException(GlobalError.HTTP_TOO_MANY_ATTEMPTS);
        }
    }

    void onFailure(String email) {
        String key = key(email);
        String raw = redis.opsForValue().get(key);

        LockState state;
        if (raw != null) {
            state = parse(raw);
            if (state == null) {
                state = new LockState(0, 0);
            }
        } else {
            state = new LockState(0, 0);
        }

        long newFailCount = state.failCount() + 1;
        long backoffMs = backoffMillis(newFailCount);
        long lockedUntilEpoch = Instant.now().toEpochMilli() + backoffMs;

        LockState next = new LockState(newFailCount, lockedUntilEpoch);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(next),
                    Duration.ofMillis(backoffMs + 1_000));
        } catch (JacksonException e) {
            log.error("Failed to serialize rate-limit state", e);
        }
    }

    void onSuccess(String email) {
        redis.delete(key(email));
    }

    private long backoffMillis(long failCount) {
        double factor = Math.pow(2, failCount - 1);
        long ms = (long) (props.baseDelay().toMillis() * factor);
        return Math.min(ms, props.maxDelay().toMillis());
    }

    private static String key(String email) {
        return KEY_PREFIX + email.strip().toLowerCase();
    }

    private LockState parse(String raw) {
        try {
            return objectMapper.readValue(raw, LockState.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    record LockState(long failCount, long lockedUntilEpoch) {
    }
}
