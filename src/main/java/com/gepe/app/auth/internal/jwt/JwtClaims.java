package com.gepe.app.auth.internal.jwt;

import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.platform.exception.ServiceException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

public final class JwtClaims {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLES = "roles";

    private JwtClaims() {}

    public static UUID getUserId(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        String sub = jwt.getClaimAsString(JwtClaimNames.SUB);
        if (sub == null || sub.isBlank()) throw new ServiceException(AuthError.TOKEN_INVALID_CLAIM);
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(AuthError.TOKEN_INVALID_CLAIM);
        }
    }

    public static String getEmail(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        String email = jwt.getClaimAsString(CLAIM_EMAIL);
        if (email == null) throw new ServiceException(AuthError.TOKEN_INVALID_CLAIM);
        return email;
    }

    public static List<String> getRoles(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
        return roles != null ? List.copyOf(roles) : List.of();
    }

    public static UUID getJti(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        String id = jwt.getId();
        if (id == null || id.isBlank()) throw new ServiceException(AuthError.TOKEN_INVALID_CLAIM);
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(AuthError.TOKEN_INVALID_CLAIM);
        }
    }

    public static Instant getExpiresAt(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) throw new ServiceException(AuthError.TOKEN_INVALID_CLAIM);
        return expiresAt;
    }

    public static Duration ttlUntilExpiry(Jwt jwt) {
        Instant expiresAt = getExpiresAt(jwt);
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
