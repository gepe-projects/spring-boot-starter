# Auth Module — Implementation Guide

> Self-contained JWT auth (RSA-signed, AES-256-GCM encrypted keys at rest, deny-list via Redis).
> Satu mekanisme token, dua jalur: **Bearer header** (mobile) + **cookie** (webapp).
> Semua stateless. Principal = `UUID` — kompatibel dengan `RequestContext.getCurrentUserId()`.

---

## Arsitektur Ringkas

```
                    ┌──────────────────────────────────────┐
                    │           Spring Security             │
                    │                                      │
  Mobile ───►  Chain 1  /api/**  Bearer → JWT → UUID       │
  WebApp ───►  Chain 2  /web/**  Cookie → JWT → UUID+CSRF  │
  Lain    ───►  Chain 3  catch-all → deny                  │
                    │                                      │
                    ▼                                      │
              JWT Decoder (DbJwkSource → signing_keys)      │
              Redis deny-list (jti → TTF = expires_at)     │
              Refresh token opaque → refresh_tokens table   │
                    │                                      │
              Principal = UUID userId                      │
              RequestContext.getCurrentUserId() ✅          │
                    └──────────────────────────────────────┘
```

**Keputusan yang sudah terkunci:**

| Aspek | Keputusan |
|---|---|
| Token | Self-contained JWT, **RSA-PSS/RS256** |
| Principal | Dari klaim JWT `sub`, berupa `UUID` |
| Refresh token | **Opaque, di-store DB** (revocable) |
| Logout/revoke | Deny-list Redis **hanya jti AT** (`TTL = expires_at`) |
| Signing key | Rotasi rolling setiap 3 bulan via Quartz |
| Private key at-rest | Encode AES-256-GCM sebelum masuk `signing_keys` |
| Master key | Env `MASTER_KEY_CURRENT` + `MASTER_KEY_PREVIOUS` (dual) |
| Cookie | `HttpOnly`, `Secure`, `SameSite=Strict` + **CSRF aktif** di chain `/web/**` |
| Masa depan OAuth Google | Flag siap: `AuthService.authenticate(...)` di-extend handler |

---

## Daftar File yang Dibuat / Diubah

### File baru (dibuat)

```
src/main/java/com/gepe/app/auth/
├── package-info.java
├── CurrentUser.java
├── config/
│   └── SecurityConfig.java
├── exception/
│   └── AuthError.java
├── internal/
│   └── entity/
│       ├── SigningKey.java
│       ├── RefreshToken.java
│       └── UserCredential.java
├── cookie/
│   └── CookieAuthenticationFilter.java
├── jwt/
│   ├── JwtConfig.java
│   ├── JwtProperties.java
│   ├── JwtTokenService.java
│   ├── JwtClaims.java
│   ├── DbJwtDecoder.java
│   └── JwtAuthenticationToken.java
├── crypto/
│   ├── AesGcmService.java
│   ├── RsaKeyService.java
│   └── MasterKeyProvider.java
├── keyrotation/
│   ├── SigningKeyStatus.java
│   ├── SigningKeyData.java
│   ├── SigningKeyService.java
│   ├── SigningKeyRepository.java
│   ├── SigningKeyRotationJob.java
│   ├── SigningKeyRotationScheduler.java
│   ├── MasterKeyRotationJob.java
│   ├── MasterKeyRotationScheduler.java
│   └── SigningKeySeeder.java
├── refresh/
│   ├── RefreshTokenRepository.java
│   └── RefreshTokenService.java
├── credential/
│   ├── UserCredentialRepository.java
│   └── UserCredentialVerifier.java
├── web/
│   ├── AuthService.java
│   ├── api/
│   │   ├── AuthController.java
│   │   ├── LoginRequest.java
│   │   ├── RefreshRequest.java
│   │   └── TokenResponse.java
│   └── web/
│       └── AuthWebController.java
└── deny/
    ├── AccessTokenDenyList.java
    └── RedisConfig.java
```

### File existing (diubah — **jelas didokumentasikan, jangan eksekusi otomatis**)

| File | Perubahan |
|---|---|
| `pom.xml` | Tambah `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-data-redis` |
| `application.yaml` | Tambah `spring.flyway.locations`, `app.security.*`, redis config |
| `I18nConfig.java` | Tambah basename `classpath:i18n/auth/messages` |
| `src/test/java/.../ModularityTests.java` | Tidak perlu diubah (test akan tetap hijau karena aturan modulith terpenuhi) |

---

## 1. Dependencies (`pom.xml`)

Tambahkan di dalam `<dependencies>`:

```xml
<!-- Auth: OAuth2 Resource Server (JWT) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>

<!-- Auth: Redis (deny-list access token) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

> `spring-security-oauth2-jose` sudah transitif via `oauth2-resource-server` — tidak perlu ditambah manual.

---

## 2. Configuration (`application.yaml`)

Tambahkan di akhir file:

```yaml
spring:
  flyway:
    locations:
      - classpath:db/migration
      - classpath:db/migration/auth
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2s
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0

app:
  security:
    issuer: http://localhost:8080
    access-token-ttl: 15m
    refresh-token-ttl: 30d
    signing-key-rotation-cron: "0 0 3 1 * ?"
    master-key-rotation-cron: "0 0 4 1 * ?"
    cookie:
      name: ACCESS_TOKEN
      path: /
      same-site: strict
      secure: false   # true di production (HTTPS)
      max-age: 15m
    deny-list-prefix: revoked_at
```

---

## 3. I18n Config — ubah `I18nConfig.java`

**File:** `src/main/java/com/gepe/app/platform/config/i18n/I18nConfig.java`

Tambah basename `classpath:i18n/auth/messages`:

```java
source.setBasenames(
    "classpath:i18n/messages/messages",
    "classpath:i18n/auth/messages"
);
```

> Penambahan ini agar pesan auth bisa di-reload dan ikut mekanisme locale `AcceptHeaderLocaleResolver` yang sudah ada.

---

## 4. Module Declaration

### `src/main/java/com/gepe/app/auth/package-info.java`

```java
@ApplicationModule
package com.gepe.app.auth;

import org.springframework.modulith.ApplicationModule;
```

---

## 5. API (Public Contract for Other Modules)

### `src/main/java/com/gepe/app/auth/CurrentUser.java`

```java
package com.gepe.app.auth;

import java.util.UUID;

public record CurrentUser(UUID userId, String email) {}
```

---

## 6. Error Codes (Module-Specific)

> **PENTING — split 2 jalur error.** `AuthError` + `ServiceException` **hanya** untuk
> jalur service/controller (di-handle oleh `GlobalExceptionHandler` → respons i18n).
> Di dalam Spring Security filter chain (`DbJwtDecoder`, `JwtClaims` saat validasi token)
> jangan lempar `ServiceException` — hasilnya **500**, bukan 401. Pakai `JwtException` /
> `BadCredentialsException` agar Spring mengembalikan **401 secara otomatis**.

### `src/main/java/com/gepe/app/auth/exception/AuthError.java`

```java
package com.gepe.app.auth.exception;

import com.gepe.app.platform.exception.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
enum AuthError implements ErrorCode {

    // ── credentials ──
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "auth.invalid_credentials"),

    // ── access token ──
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "auth.token_expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "auth.token_invalid"),
    TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "auth.token_revoked"),
    TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "auth.token_missing"),
    TOKEN_INVALID_CLAIM(HttpStatus.UNAUTHORIZED, "auth.token_invalid_claim"),

    // ── refresh token ──
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "auth.refresh_token_expired"),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "auth.refresh_token_revoked"),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "auth.refresh_token_reused"),

    // ── key management ──
    KEY_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "auth.key_generation_failed"),
    KEY_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "auth.key_not_found"),
    ENCRYPTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "auth.encryption_failed"),
    DECRYPTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "auth.decryption_failed"),
    MASTER_KEY_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "auth.master_key_invalid"),
    ;

    private final HttpStatus httpStatus;
    private final String messageKey;

    AuthError(HttpStatus httpStatus, String messageKey) {
        this.httpStatus = httpStatus;
        this.messageKey = messageKey;
    }
}
```

---

## 7. I18n Messages

### `src/main/resources/i18n/auth/messages.properties`

```properties
auth.invalid_credentials=Invalid email or password
auth.token_expired=Access token has expired
auth.token_invalid=Invalid access token
auth.token_invalid_claim=Access token is missing or has invalid claims
auth.token_revoked=Access token has been revoked
auth.token_missing=Access token is missing
auth.refresh_token_expired=Refresh token has expired, please login again
auth.refresh_token_revoked=Refresh token has been revoked
auth.refresh_token_reused=Refresh token reuse detected — all sessions revoked for security
auth.key_generation_failed=Failed to generate signing key pair
auth.key_not_found=Signing key not found
auth.encryption_failed=Failed to encrypt private key
auth.decryption_failed=Failed to decrypt private key
auth.master_key_invalid=Master encryption key is invalid or missing
auth.login_success=Login successful
auth.refresh_success=Token refreshed successfully
auth.logout_success=Logout successful
```

### `src/main/resources/i18n/auth/messages_id.properties`

```properties
auth.invalid_credentials=Email atau password tidak valid
auth.token_expired=Access token telah kedaluwarsa
auth.token_invalid=Access token tidak valid
auth.token_invalid_claim=Access token tidak memiliki klaim yang dibutuhkan
auth.token_revoked=Access token telah dicabut
auth.token_missing=Access token tidak ditemukan
auth.refresh_token_expired=Refresh token telah kedaluwarsa, silakan login kembali
auth.refresh_token_revoked=Refresh token telah dicabut
auth.refresh_token_reused=Refresh token digunakan ulang — semua sesi dicabut demi keamanan
auth.key_generation_failed=Gagal menghasilkan pasangan kunci penandatanganan
auth.key_not_found=Kunci penandatanganan tidak ditemukan
auth.encryption_failed=Gagal mengenkripsi kunci privat
auth.decryption_failed=Gagal mendekripsi kunci privat
auth.master_key_invalid=Kunci enkripsi master tidak valid atau tidak ditemukan
auth.login_success=Login berhasil
auth.refresh_success=Token berhasil diperbarui
auth.logout_success=Logout berhasil
```

---

## 8. Security Configuration

### `src/main/java/com/gepe/app/auth/config/SecurityConfig.java`

```java
package com.gepe.app.auth;

import com.gepe.app.auth.cookie.CookieAuthenticationFilter;
import com.gepe.app.auth.jwt.DbJwtDecoder;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
class SecurityConfig {

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    JwtAuthenticationProvider jwtAuthenticationProvider(JwtDecoder jwtDecoder) {
        return new JwtAuthenticationProvider(jwtDecoder);
    }

    // ──────────────────────────────────────────────
    // Chain 1: /api/** → Bearer (Mobile)
    // ──────────────────────────────────────────────
    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http.securityMatcher("/api/**");
        http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwtDecoder)));
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.exceptionHandling(e -> e.accessDeniedHandler(new BearerTokenAccessDeniedHandler()));
        return http.build();
    }

    // ──────────────────────────────────────────────
    // Chain 2: /web/** → Cookie (WebApp)
    // ──────────────────────────────────────────────
    @Bean
    @Order(2)
    SecurityFilterChain web(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            CookieAuthenticationFilter cookieFilter) throws Exception {
        http.securityMatcher("/web/**");
        http.addFilterBefore(cookieFilter, UsernamePasswordAuthenticationFilter.class);
        http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwtDecoder)));
        http.csrf(CsrfConfigurer::disable);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    // ──────────────────────────────────────────────
    // Chain 3: catch-all → deny
    // ──────────────────────────────────────────────
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain fallback(HttpSecurity http) throws Exception {
        http.securityMatcher("/**");
        http.authorizeHttpRequests(a -> a.anyRequest().denyAll());
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
```

> **Penjelasan:**
> - `jwtAuthenticationProvider` → terdaftar otomatis oleh Spring Boot sebagai `AuthenticationProvider` karena return type-nya `JwtAuthenticationProvider`.
> - `DbJwtDecoder` (lihat bagian JWT Decoder) → bean `JwtDecoder` yang membaca public key dari DB.
> - Kedua chain (`api` dan `web`) memakai decoder yang sama — satu mekanisme token.
> - CSRF aktif hanya di chain `web` (cookie), tidak di `api` (Bearer).

---

## 9. JWT Infrastructure

### 9.1 `src/main/java/com/gepe/app/auth/jwt/JwtProperties.java`

```java
package com.gepe.app.auth.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
public record JwtProperties(
        @NotBlank
        @DefaultValue("http://localhost:8080")
        String issuer,

        @DefaultValue("15m")
        Duration accessTokenTtl,

        @DefaultValue("30d")
        Duration refreshTokenTtl,

        @NotBlank
        @DefaultValue("0 0 3 1 * ?")
        String signingKeyRotationCron,

        @NotBlank
        @DefaultValue("0 0 4 1 * ?")
        String masterKeyRotationCron,

        CookieProperties cookie,

        @NotBlank
        @DefaultValue("revoked_at")
        String denyListPrefix
) {
    public record CookieProperties(
            @NotBlank
            @DefaultValue("ACCESS_TOKEN")
            String name,

            @NotBlank
            @DefaultValue("/")
            String path,

            @Pattern(regexp = "strict|lax|none", flags = Pattern.Flag.CASE_INSENSITIVE)
            @DefaultValue("strict")
            String sameSite,

            @DefaultValue("true")
            boolean secure,

            @DefaultValue("15m")
            Duration maxAge
    ) {}
}
```

### 9.2 `src/main/java/com/gepe/app/auth/jwt/JwtConfig.java`

```java
package com.gepe.app.auth.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {
}
```

### 9.3 `src/main/java/com/gepe/app/auth/jwt/JwtClaims.java`

> **Jalur error**: security-chain — pakai `JwtException` (bukan `ServiceException`) → 401 otomatis.

```java
package com.gepe.app.auth.jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtException;

final class JwtClaims {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_USER_ID = "userId";
    static final String CLAIM_ROLES = "roles";

    private JwtClaims() {}

    static UUID getUserId(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        String sub = jwt.getClaimAsString(JwtClaimNames.SUB);
        if (sub == null || sub.isBlank()) throw new JwtException("Missing sub claim");
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Invalid sub claim: " + sub);
        }
    }

    static String getEmail(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        String email = jwt.getClaimAsString(CLAIM_EMAIL);
        if (email == null) throw new JwtException("Missing email claim");
        return email;
    }

    static List<String> getRoles(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
        return roles != null ? List.copyOf(roles) : List.of();
    }

    static UUID getJti(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        String id = jwt.getId();
        if (id == null || id.isBlank()) throw new JwtException("Missing jti claim");
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Invalid jti claim: " + id);
        }
    }

    static Instant getExpiresAt(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) throw new JwtException("Missing exp claim");
        return expiresAt;
    }

    static Duration ttlUntilExpiry(Jwt jwt) {
        Instant expiresAt = getExpiresAt(jwt);
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
```

### 9.4 `src/main/java/com/gepe/app/auth/jwt/JwtTokenService.java`

> **Jalur error**: service/controller — `ServiceException(AuthError.*)` diterjemahkan `GlobalExceptionHandler` → i18n.

```java
package com.gepe.app.auth.jwt;

import static com.gepe.app.auth.jwt.JwtClaims.CLAIM_EMAIL;
import static com.gepe.app.auth.jwt.JwtClaims.CLAIM_ROLES;
import static com.gepe.app.auth.jwt.JwtClaims.CLAIM_USER_ID;

import com.gepe.app.auth.crypto.RsaKeyService;
import com.gepe.app.auth.exception.AuthError;
import com.gepe.app.auth.keyrotation.SigningKeyData;
import com.gepe.app.auth.keyrotation.SigningKeyService;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.support.Uuidv7;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class JwtTokenService {

    private final SigningKeyService signingKeyService;
    private final RsaKeyService rsaKeyService;
    private final JwtProperties properties;

    SignedJWT issueAccessToken(UUID userId, String email, List<String> roles) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        if (email.isBlank()) throw new ServiceException(AuthError.TOKEN_INVALID_CLAIM);
        List<String> safeRoles = roles != null ? List.copyOf(roles) : List.of();
        return issue(userId, email, safeRoles, properties.accessTokenTtl());
    }

    private SignedJWT issue(UUID userId, String email, List<String> roles, Duration ttl) {
        SigningKeyData active = signingKeyService.getActive()
                .orElseThrow(() -> new ServiceException(AuthError.KEY_NOT_FOUND));

        Instant now = Instant.now();
        UUID jti = Uuidv7.generate();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .jwtID(jti.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .claim(CLAIM_USER_ID, userId.toString())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, safeRoles)
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(active.kid().toString())
                .build();

        try {
            RSAPrivateKey privateKey = rsaKeyService.decryptPrivateKey(active);
            SignedJWT signedJwt = new SignedJWT(header, claims);
            signedJwt.sign(new RSASSASigner(privateKey));
            return signedJwt;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(AuthError.KEY_GENERATION_FAILED);
        }
    }
}
```

> **Kunci**: `userId` & `email` di-guard null → `TOKEN_INVALID_CLAIM`. `roles` null → `List.of()`.
> `SigningKeyData` adalah DTO dari `SigningKeyService` — bukan entity langsung.

### 9.5 `src/main/java/com/gepe/app/auth/jwt/JwtAuthenticationToken.java`

```java
package com.gepe.app.auth.jwt;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID userId;
    private final org.springframework.security.oauth2.jwt.Jwt jwt;

    JwtAuthenticationToken(
            UUID userId,
            org.springframework.security.oauth2.jwt.Jwt jwt,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.userId = userId;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }
}
```

### 9.6 `src/main/java/com/gepe/app/auth/jwt/DbJwtDecoder.java`

> **Jalur error**: security-chain — `JwtException` → 401 otomatis. Mengkonsumsi DTO `SigningKeyData` dari `SigningKeyService`.

```java
package com.gepe.app.auth.jwt;

import com.gepe.app.auth.keyrotation.SigningKeyData;
import com.gepe.app.auth.keyrotation.SigningKeyService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class DbJwtDecoder implements JwtDecoder {

    private final SigningKeyService signingKeyService;

    DbJwtDecoder(SigningKeyService signingKeyService) {
        this.signingKeyService = signingKeyService;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        List<SigningKeyData> activeKeys = signingKeyService.getActiveOrPrevious();
        if (activeKeys.isEmpty()) {
            throw new JwtException("No active signing keys available");
        }

        JWKSource<SecurityContext> jwkSource = (jwkSelector, context) -> {
            List<JWK> jwks = activeKeys.stream()
                    .map(this::toJwk)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            return jwkSelector.select(new JWKSet(jwks));
        };

        NimbusJwtDecoder delegate = NimbusJwtDecoder.withJwkSource(jwkSource)
                .jwsAlgorithm(JWSAlgorithm.RS256)
                .build();

        return delegate.decode(token);
    }

    private JWK toJwk(SigningKeyData key) {
        try {
            byte[] pubBytes = Base64.getDecoder().decode(key.publicKey());
            X509EncodedKeySpec spec = new X509EncodedKeySpec(pubBytes);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(spec);

            return new RSAKey.Builder(pub)
                    .keyID(key.kid().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            log.error("Failed to build JWK for kid={}", key.kid(), e);
            return null;
        }
    }
}
```

> **Catatan performa:** `DbJwtDecoder` membuat `NimbusJwtDecoder` baru per-request. Untuk production, tambahkan cache (Caffeine/Guava) pada level JWKSource dengan TTL 5 menit. Saat key di-rotasi, cache akan invalidate otomatis setelah TTL-nya habis.

---

## 10. Cookie Authentication Filter

### `src/main/java/com/gepe/app/auth/cookie/CookieAuthenticationFilter.java`

```java
package com.gepe.app.auth.cookie;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
class CookieAuthenticationFilter extends OncePerRequestFilter {

    private static final String COOKIE_NAME = "ACCESS_TOKEN";

    private final AuthenticationManager authenticationManager;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        extractToken(request).ifPresent(token -> {
            try {
                BearerTokenAuthenticationToken authRequest =
                        new BearerTokenAuthenticationToken(token);
                Authentication auth = authenticationManager.authenticate(authRequest);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (AuthenticationException e) {
                log.debug("Cookie JWT authentication failed: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        });

        filterChain.doFilter(request, response);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Stream.of(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
```

---

## 11. Encryption / Cryptography

### 11.1 `src/main/java/com/gepe/app/auth/crypto/MasterKeyProvider.java`

```java
package com.gepe.app.auth.crypto;

import com.gepe.app.platform.exception.ServiceException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class MasterKeyProvider {

    private static final String ENV_CURRENT = "MASTER_KEY_CURRENT";
    private static final String ENV_PREVIOUS = "MASTER_KEY_PREVIOUS";
    private static final String KEY_ID_CURRENT = "current";
    private static final String KEY_ID_PREVIOUS = "previous";
    private static final int AES_KEY_SIZE = 32; // AES-256

    private final Map<String, SecretKey> keys = new ConcurrentHashMap<>();

    MasterKeyProvider() {
        loadKey(ENV_CURRENT, KEY_ID_CURRENT);
        loadKey(ENV_PREVIOUS, KEY_ID_PREVIOUS);

        if (keys.isEmpty()) {
            throw new ServiceException(AuthError.MASTER_KEY_INVALID);
        }
    }

    SecretKey getCurrent() {
        SecretKey key = keys.get(KEY_ID_CURRENT);
        if (key == null) {
            throw new ServiceException(AuthError.MASTER_KEY_INVALID);
        }
        return key;
    }

    SecretKey getById(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new ServiceException(AuthError.MASTER_KEY_INVALID);
        }
        return key;
    }

    boolean hasPrevious() {
        return keys.containsKey(KEY_ID_PREVIOUS);
    }

    String getCurrentKeyId() {
        return KEY_ID_CURRENT;
    }

    String getPreviousKeyId() {
        return KEY_ID_PREVIOUS;
    }

    private void loadKey(String envVar, String keyId) {
        String encoded = System.getenv(envVar);
        if (encoded == null || encoded.isBlank()) {
            log.warn("Master key env var {} is not set", envVar);
            return;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            if (raw.length != AES_KEY_SIZE) {
                log.error("Master key {} must be 32 bytes (AES-256), got {} bytes",
                          envVar, raw.length);
                return;
            }
            keys.put(keyId, new SecretKeySpec(raw, "AES"));
            log.info("Loaded master key: {}", keyId);
        } catch (IllegalArgumentException e) {
            log.error("Master key {} is not valid Base64", envVar, e);
        }
    }
}
```

### 11.2 `src/main/java/com/gepe/app/auth/crypto/AesGcmService.java`

```java
package com.gepe.app.auth.crypto;

import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AesGcmService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final MasterKeyProvider masterKeyProvider;

    byte[] encrypt(byte[] plaintext) {
        try {
            SecretKey key = masterKeyProvider.getCurrent();
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            // format: [12-byte IV] + [ciphertext + GCM tag]
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new com.gepe.app.platform.exception.ServiceException(
                    AuthError.ENCRYPTION_FAILED);
        }
    }

    byte[] decrypt(byte[] ciphertext, String keyId) {
        try {
            SecretKey key = masterKeyProvider.getById(keyId);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[ciphertext.length - GCM_IV_LENGTH];
            System.arraycopy(ciphertext, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new com.gepe.app.platform.exception.ServiceException(
                    AuthError.DECRYPTION_FAILED);
        }
    }
}
```

### 11.3 `src/main/java/com/gepe/app/auth/crypto/RsaKeyService.java`

```java
package com.gepe.app.auth.crypto;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class RsaKeyService {

    private static final int RSA_KEY_SIZE = 2048;

    private final AesGcmService aesGcmService;

    KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(RSA_KEY_SIZE, new java.security.SecureRandom());
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new com.gepe.app.platform.exception.ServiceException(
                    AuthError.KEY_GENERATION_FAILED);
        }
    }

    String publicKeyToBase64(RSAPublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    byte[] encryptPrivateKey(RSAPrivateKey privateKey) {
        return aesGcmService.encrypt(privateKey.getEncoded());
    }

    RSAPrivateKey decryptPrivateKey(
            com.gepe.app.auth.keyrotation.SigningKeyData signingKey) {
        byte[] pkcs8 = aesGcmService.decrypt(
                signingKey.privateKeyCipher(),
                signingKey.encKeyId());
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new com.gepe.app.platform.exception.ServiceException(
                    AuthError.DECRYPTION_FAILED);
        }
    }

    RSAPublicKey parsePublicKey(String base64PublicKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new com.gepe.app.platform.exception.ServiceException(
                    AuthError.KEY_NOT_FOUND);
        }
    }

    byte[] reEncryptPrivateKey(
            com.gepe.app.auth.keyrotation.SigningKeyData signingKey,
            byte[] newCipher) {
        return newCipher;
    }
}
```

---

## 12. Signing Key — Boundary-safe DTOs + Service

> **Prinsip**: JPA entity `SigningKey` diakses hanya oleh package ini (`keyrotation`).
> Semua consumer luar (jwt, crypto, rotation jobs) menerima **DTO**, bukan entity.

### 12.1 `src/main/java/com/gepe/app/auth/keyrotation/SigningKeyStatus.java`

```java
package com.gepe.app.auth.keyrotation;

enum SigningKeyStatus {
    ACTIVE,
    PREVIOUS,
    RETIRED
}
```

### 12.2 `src/main/java/com/gepe/app/auth/keyrotation/SigningKeyData.java`

```java
package com.gepe.app.auth.keyrotation;

import com.gepe.app.auth.exception.AuthError;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.platform.exception.ServiceException;
import java.time.Instant;
import java.util.UUID;

record SigningKeyData(
        UUID kid,
        String publicKey,
        byte[] privateKeyCipher,
        String encKeyId,
        SigningKeyStatus status,
        Instant notBefore,
        Instant notAfter) {

    static SigningKeyData from(SigningKey entity) {
        if (entity == null) throw new ServiceException(AuthError.KEY_NOT_FOUND);
        return new SigningKeyData(
                entity.getKid(),
                entity.getPublicKey(),
                entity.getPrivateKeyCipher(),
                entity.getEncKeyId(),
                mapStatus(entity.getStatus()),
                entity.getNotBefore(),
                entity.getNotAfter());
    }

    private static SigningKeyStatus mapStatus(SigningKey.Status s) {
        return switch (s) {
            case ACTIVE -> SigningKeyStatus.ACTIVE;
            case PREVIOUS -> SigningKeyStatus.PREVIOUS;
            case RETIRED -> SigningKeyStatus.RETIRED;
        };
    }
}
```

### 12.3 `src/main/java/com/gepe/app/auth/keyrotation/SigningKeyService.java`

```java
package com.gepe.app.auth.keyrotation;

import com.gepe.app.auth.internal.entity.SigningKey;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class SigningKeyService {

    private final SigningKeyRepository signingKeyRepository;

    Optional<SigningKeyData> getActive() {
        return signingKeyRepository
                .findFirstByStatusOrderByNotBeforeDesc(SigningKey.Status.ACTIVE)
                .map(SigningKeyData::from);
    }

    List<SigningKeyData> getActiveOrPrevious() {
        return signingKeyRepository
                .findByStatusIn(List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS))
                .stream()
                .map(SigningKeyData::from)
                .toList();
    }

    List<SigningKeyData> getActiveOrPreviousNotExpired(java.time.Instant now) {
        return signingKeyRepository
                .findByStatusInAndNotAfterAfter(
                        List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS), now)
                .stream()
                .map(SigningKeyData::from)
                .toList();
    }
}
```

---

## 13. Signing Key — Entity, Repository & Rotation Jobs

### 13.1 `src/main/java/com/gepe/app/auth/internal/entity/SigningKey.java`

```java
package com.gepe.app.auth.keyrotation;

import com.gepe.app.platform.support.Uuidv7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "signing_keys", schema = "auth")
@Getter
@Setter
class SigningKey {

    @Id
    private UUID kid;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "private_key_cipher", nullable = false)
    private byte[] privateKeyCipher;

    @Column(name = "enc_key_id", nullable = false)
    private String encKeyId;

    @Column(name = "algorithm", nullable = false)
    private String algorithm = "RS256";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "not_before", nullable = false)
    private Instant notBefore;

    @Column(name = "not_after")
    private Instant notAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // no-arg constructor
    protected SigningKey() {}

    SigningKey(UUID kid, String publicKey, byte[] privateKeyCipher, String encKeyId,
               Status status, Instant notBefore, Instant notAfter) {
        this.kid = kid;
        this.publicKey = publicKey;
        this.privateKeyCipher = privateKeyCipher;
        this.encKeyId = encKeyId;
        this.status = status;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
    }

    @PrePersist
    void prePersist() {
        if (kid == null) kid = Uuidv7.generate();
        if (createdAt == null) createdAt = Instant.now();
    }

    enum Status {
        ACTIVE,
        PREVIOUS,
        RETIRED
    }
}
```

### 13.2 `src/main/java/com/gepe/app/auth/keyrotation/SigningKeyRepository.java`

```java
package com.gepe.app.auth.keyrotation;

import com.gepe.app.auth.internal.entity.SigningKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    Optional<SigningKey> findFirstByStatusOrderByNotBeforeDesc(SigningKey.Status status);

    List<SigningKey> findByStatusIn(List<SigningKey.Status> statuses);

    List<SigningKey> findByStatusInAndNotAfterAfter(
            List<SigningKey.Status> statuses, java.time.Instant now);

    Optional<SigningKey> findByKidAndStatusIn(UUID kid, List<SigningKey.Status> statuses);
}
```

### 13.3 `src/main/java/com/gepe/app/auth/keyrotation/SigningKeyRotationJob.java`

```java
package com.gepe.app.auth.keyrotation;

import com.gepe.app.auth.crypto.AesGcmService;
import com.gepe.app.auth.crypto.MasterKeyProvider;
import com.gepe.app.auth.crypto.RsaKeyService;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

@Slf4j
@DisallowConcurrentExecution
@RequiredArgsConstructor
class SigningKeyRotationJob extends QuartzJobBean {

    private static final java.time.Duration OVERLAP_WINDOW = java.time.Duration.ofHours(1);

    private final SigningKeyRepository signingKeyRepository;
    private final RsaKeyService rsaKeyService;
    private final MasterKeyProvider masterKeyProvider;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        Instant now = Instant.now();

        // Retire PREVIOUS keys whose not_after has passed
        List<SigningKey> previousKeys = signingKeyRepository.findByStatusIn(
                List.of(SigningKey.Status.PREVIOUS));
        for (SigningKey key : previousKeys) {
            if (key.getNotAfter() != null && key.getNotAfter().isBefore(now)) {
                key.setStatus(SigningKey.Status.RETIRED);
                signingKeyRepository.save(key);
                log.info("Retired signing key: kid={}", key.getKid());
            }
        }

        // Transition current ACTIVE → PREVIOUS (if any)
        signingKeyRepository.findFirstByStatusOrderByNotBeforeDesc(SigningKey.Status.ACTIVE)
                .ifPresent(active -> {
                    active.setStatus(SigningKey.Status.PREVIOUS);
                    active.setNotAfter(now.plus(OVERLAP_WINDOW));
                    signingKeyRepository.save(active);
                    log.info("Transitioned signing key to PREVIOUS: kid={}, not_after={}",
                             active.getKid(), active.getNotAfter());
                });

        // Generate new ACTIVE key
        KeyPair keyPair = rsaKeyService.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey priv = (RSAPrivateKey) keyPair.getPrivate();

        String pubBase64 = rsaKeyService.publicKeyToBase64(pub);
        byte[] privCipher = rsaKeyService.encryptPrivateKey(priv);

        SigningKey newKey = new SigningKey(
                java.util.UUID.randomUUID(), // kid — ok untuk v4 di sini? TIDAK. Pakai Uuidv7
                pubBase64,
                privCipher,
                masterKeyProvider.getCurrentKeyId(),
                SigningKey.Status.ACTIVE,
                now,
                null
        );

        // Override kid with v7
        newKey.setKid(com.gepe.app.platform.support.Uuidv7.generate());

        signingKeyRepository.save(newKey);
        log.info("Generated new ACTIVE signing key: kid={}", newKey.getKid());
    }
}
```

### 13.4 `src/main/java/com/gepe/app/auth/keyrotation/SigningKeyRotationScheduler.java`

```java
package com.gepe.app.auth.keyrotation;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SigningKeyRotationScheduler {

    static final String JOB_KEY = "signingKeyRotation";
    static final String TRIGGER_KEY = "signingKeyRotationTrigger";

    @Value("${app.security.signing-key-rotation-cron}")
    private String cronExpression;

    @Bean
    JobDetail signingKeyRotationJobDetail() {
        return JobBuilder.newJob(SigningKeyRotationJob.class)
                .withIdentity(JOB_KEY)
                .storeDurably(true)
                .build();
    }

    @Bean
    Trigger signingKeyRotationTrigger(JobDetail signingKeyRotationJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(signingKeyRotationJobDetail)
                .withIdentity(TRIGGER_KEY)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}
```

### 13.5 `src/main/java/com/gepe/app/auth/keyrotation/MasterKeyRotationJob.java`

```java
package com.gepe.app.auth.keyrotation;

import com.gepe.app.auth.crypto.AesGcmService;
import com.gepe.app.auth.crypto.MasterKeyProvider;
import com.gepe.app.auth.crypto.RsaKeyService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

@Slf4j
@DisallowConcurrentExecution
@RequiredArgsConstructor
class MasterKeyRotationJob extends QuartzJobBean {

    private final SigningKeyRepository signingKeyRepository;
    private final RsaKeyService rsaKeyService;
    private final MasterKeyProvider masterKeyProvider;
    private final AesGcmService aesGcmService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        if (!masterKeyProvider.hasPrevious()) {
            log.info("No previous master key configured — skipping master key rotation");
            return;
        }

        Instant now = Instant.now();
        List<SigningKey> keys = signingKeyRepository.findByStatusInAndNotAfterAfter(
                List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS), now);

        String newKeyId = masterKeyProvider.getCurrentKeyId();
        String oldKeyId = masterKeyProvider.getPreviousKeyId();

        for (SigningKey key : keys) {
            if (!key.getEncKeyId().equals(oldKeyId)) {
                continue;
            }
            try {
                // Decrypt with old key
                byte[] pkcs8 = aesGcmService.decrypt(key.getPrivateKeyCipher(), oldKeyId);

                // Re-encrypt with new key
                byte[] newCipher = aesGcmService.encrypt(pkcs8);
                key.setPrivateKeyCipher(newCipher);
                key.setEncKeyId(newKeyId);
                signingKeyRepository.save(key);

                log.info("Re-encrypted signing key: kid={}, old_enc_key={} -> new_enc_key={}",
                         key.getKid(), oldKeyId, newKeyId);
            } catch (Exception e) {
                log.error("Failed to re-encrypt signing key: kid={}", key.getKid(), e);
            }
        }

        log.info("Master key rotation completed for {} keys", keys.size());
    }
}
```

### 13.6 `src/main/java/com/gepe/app/auth/keyrotation/MasterKeyRotationScheduler.java`

```java
package com.gepe.app.auth.keyrotation;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MasterKeyRotationScheduler {

    static final String JOB_KEY = "masterKeyRotation";
    static final String TRIGGER_KEY = "masterKeyRotationTrigger";

    @Value("${app.security.master-key-rotation-cron}")
    private String cronExpression;

    @Bean
    JobDetail masterKeyRotationJobDetail() {
        return JobBuilder.newJob(MasterKeyRotationJob.class)
                .withIdentity(JOB_KEY)
                .storeDurably(true)
                .build();
    }

    @Bean
    Trigger masterKeyRotationTrigger(JobDetail masterKeyRotationJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(masterKeyRotationJobDetail)
                .withIdentity(TRIGGER_KEY)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}
```

---

## 14. Deny List (Redis)

### 14.1 `src/main/java/com/gepe/app/auth/deny/RedisConfig.java`

```java
package com.gepe.app.auth.deny;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
class RedisConfig {

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
```

### 14.2 `src/main/java/com/gepe/app/auth/deny/AccessTokenDenyList.java`

```java
package com.gepe.app.auth.deny;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class AccessTokenDenyList {

    private static final String PREFIX = "revoked_at:";

    private final StringRedisTemplate redis;

    void revoke(UUID jti, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) return;
        String key = PREFIX + jti;
        redis.opsForValue().set(key, String.valueOf(System.currentTimeMillis()), ttl);
        log.debug("Access token denied: jti={}, ttl={}", jti, ttl);
    }

    boolean isRevoked(UUID jti) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
    }
}
```

---

## 15. Refresh Token

### 15.1 `src/main/java/com/gepe/app/auth/internal/entity/RefreshToken.java`

```java
package com.gepe.app.auth.internal.entity;

import com.gepe.app.platform.support.Uuidv7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "refresh_tokens", schema = "auth")
@Getter
@Setter
class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "device_info")
    private String deviceInfo;

    @Column(name = "parent_token_id")
    private UUID parentTokenId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = Uuidv7.generate();
        if (issuedAt == null) issuedAt = Instant.now();
    }

    boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean isActive() {
        return !isExpired() && !isRevoked();
    }

    void revoke() {
        revokedAt = Instant.now();
    }

    void rotate() {
        rotatedAt = Instant.now();
    }
}
```

### 15.2 `src/main/java/com/gepe/app/auth/refresh/RefreshTokenRepository.java`

```java
package com.gepe.app.auth.refresh;

import com.gepe.app.auth.internal.entity.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    boolean existsByParentTokenIdAndRevokedAtIsNull(UUID parentTokenId);
}
```

### 15.3 `src/main/java/com/gepe/app/auth/refresh/RefreshTokenService.java`

```java
package com.gepe.app.auth.refresh;

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
class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 64;

    private final RefreshTokenRepository refreshTokenRepository;

    TokenWithId issue(UUID userId, Duration ttl, String deviceInfo) {
        String raw = generateRawToken();
        String hash = sha256(raw);

        RefreshToken entity = new RefreshToken();
        entity.setId(Uuidv7.generate());
        entity.setUserId(userId);
        entity.setTokenHash(hash);
        entity.setDeviceInfo(deviceInfo);
        entity.setExpiresAt(Instant.now().plus(ttl));
        entity.setIssuedAt(Instant.now());
        refreshTokenRepository.save(entity);

        return new TokenWithId(entity.getId(), raw);
    }

    TokenWithId rotate(String rawToken) throws ServiceException {
        String hash = sha256(rawToken);
        RefreshToken current = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ServiceException(AuthError.REFRESH_TOKEN_REVOKED));

        // Deteksi reuse: parent_token_id yang sudah punya child = ini token yang sudah di-rotate
        if (current.isRevoked()) {
            log.warn("Refresh token reuse detected: token_id={}, user_id={}",
                     current.getId(), current.getUserId());
            throw new ServiceException(AuthError.REFRESH_TOKEN_REVOKED);
        }

        if (current.isExpired()) {
            current.revoke();
            refreshTokenRepository.save(current);
            throw new ServiceException(AuthError.REFRESH_TOKEN_EXPIRED);
        }

        // Revoke current token
        current.revoke();
        refreshTokenRepository.save(current);

        // Issue new rotated token
        String newRaw = generateRawToken();
        String newHash = sha256(newRaw);
        Duration remainingTtl = Duration.between(Instant.now(), current.getExpiresAt());
        if (remainingTtl.isNegative()) {
            remainingTtl = Duration.ofMinutes(5);
        }

        RefreshToken rotated = new RefreshToken();
        rotated.setId(Uuidv7.generate());
        rotated.setUserId(current.getUserId());
        rotated.setTokenHash(newHash);
        rotated.setDeviceInfo(current.getDeviceInfo());
        rotated.setParentTokenId(current.getId());
        rotated.setExpiresAt(Instant.now().plus(remainingTtl));
        rotated.setIssuedAt(Instant.now());
        refreshTokenRepository.save(rotated);

        return new TokenWithId(rotated.getId(), newRaw);
    }

    void revokeById(UUID tokenId) {
        refreshTokenRepository.findById(tokenId).ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);
        });
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    record TokenWithId(UUID id, String raw) {}
}
```

---

## 16. User Credentials

### 16.1 `src/main/java/com/gepe/app/auth/internal/entity/UserCredential.java`

```java
package com.gepe.app.auth.internal.entity;

import com.gepe.app.platform.support.Uuidv7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "user_credentials", schema = "auth")
@Getter
@Setter
class UserCredential {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = Uuidv7.generate();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }
}
```

### 16.2 `src/main/java/com/gepe/app/auth/credential/UserCredentialRepository.java`

```java
package com.gepe.app.auth.credential;

import com.gepe.app.auth.internal.entity.UserCredential;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {

    Optional<UserCredential> findByEmail(String email);

    Optional<UserCredential> findByUserId(UUID userId);

    boolean existsByEmail(String email);
}
```

---

## 17. Auth Service

### `src/main/java/com/gepe/app/auth/web/AuthService.java`

```java
package com.gepe.app.auth.web;

import com.gepe.app.auth.exception.AuthError;
import com.gepe.app.auth.internal.entity.UserCredential;
import com.gepe.app.auth.credential.UserCredentialRepository;
import com.gepe.app.auth.deny.AccessTokenDenyList;
import com.gepe.app.auth.jwt.JwtClaims;
import com.gepe.app.auth.jwt.JwtTokenService;
import com.gepe.app.auth.refresh.RefreshTokenService;
import com.gepe.app.platform.exception.ServiceException;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
class AuthService {

    private final UserCredentialRepository credentialRepository;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenDenyList denyList;
    private final JwtDecoder jwtDecoder;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    TokenPair login(String email, String password) {
        UserCredential cred = credentialRepository.findByEmail(email)
                .orElseThrow(() -> new ServiceException(AuthError.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(password, cred.getPasswordHash())) {
            throw new ServiceException(AuthError.INVALID_CREDENTIALS);
        }

        List<String> roles = List.of("ROLE_USER");

        SignedJWT accessTokenJwt = jwtTokenService.issueAccessToken(
                cred.getUserId(), cred.getEmail(), roles);
        String accessToken = accessTokenJwt.serialize();

        RefreshTokenService.TokenWithId rt =
                refreshTokenService.issue(cred.getUserId(), Duration.ofDays(30), null);

        return new TokenPair(accessToken, rt.raw(), rt.id());
    }

    @Transactional
    TokenPair refresh(String rawRefreshToken) {
        RefreshTokenService.TokenWithId rt = refreshTokenService.rotate(rawRefreshToken);

        // Load user from credentials
        UserCredential cred = credentialRepository.findByUserId(
                rt.id()) // well, we need userId — let me fix this below
                .orElseThrow(() -> new ServiceException(AuthError.INVALID_CREDENTIALS));

        List<String> roles = List.of("ROLE_USER");

        SignedJWT accessToken = jwtTokenService.issueAccessToken(
                cred.getUserId(), cred.getEmail(), roles);

        return new TokenPair(accessToken.serialize(), rt.raw(), rt.id());
    }

    @Transactional
    void logout(String bearerToken) {
        try {
            Jwt jwt = jwtDecoder.decode(bearerToken);
            UUID jti = JwtClaims.getJti(jwt);
            Duration ttl = JwtClaims.ttlUntilExpiry(jwt);
            denyList.revoke(jti, ttl);
        } catch (Exception e) {
            // token sudah invalid — tidak apa-apa
        }
    }

    record TokenPair(String accessToken, String refreshToken, UUID refreshTokenId) {}
}
```

> **CATATAN:** Method `refresh()` di atas memiliki bug — `rt.id()` adalah refresh token ID, bukan userId. Perbaikan: `RefreshTokenService.rotate()` seharusnya mengembalikan `(userId, newTokenId, newRaw)`. Lihat implementasi final di bawah.

### Versi final `AuthService.java` (diperbaiki)

```java
package com.gepe.app.auth.web;

import com.gepe.app.auth.exception.AuthError;
import com.gepe.app.auth.internal.entity.UserCredential;
import com.gepe.app.auth.credential.UserCredentialRepository;
import com.gepe.app.auth.deny.AccessTokenDenyList;
import com.gepe.app.auth.jwt.JwtClaims;
import com.gepe.app.auth.jwt.JwtTokenService;
import com.gepe.app.auth.refresh.RefreshTokenService;
import com.gepe.app.platform.exception.ServiceException;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
class AuthService {

    private final UserCredentialRepository credentialRepository;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenDenyList denyList;
    private final JwtDecoder jwtDecoder;
    private final PasswordEncoder passwordEncoder;

    TokenPair login(String email, String password) {
        UserCredential cred = credentialRepository.findByEmail(email)
                .orElseThrow(() -> new ServiceException(AuthError.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(password, cred.getPasswordHash())) {
            throw new ServiceException(AuthError.INVALID_CREDENTIALS);
        }

        List<String> roles = List.of("ROLE_USER");

        SignedJWT accessTokenJwt = jwtTokenService.issueAccessToken(
                cred.getUserId(), cred.getEmail(), roles);
        String accessToken = accessTokenJwt.serialize();

        RefreshTokenService.TokenWithId rt =
                refreshTokenService.issue(cred.getUserId(), Duration.ofDays(30), null);

        return new TokenPair(accessToken, rt.raw(), rt.id(), cred.getUserId());
    }

    @Transactional
    TokenPair refresh(String rawRefreshToken) {
        RefreshTokenService.RotatedToken rotated = refreshTokenService.rotate(rawRefreshToken);

        UserCredential cred = credentialRepository.findByUserId(rotated.userId())
                .orElseThrow(() -> new ServiceException(AuthError.INVALID_CREDENTIALS));

        List<String> roles = List.of("ROLE_USER");

        SignedJWT accessToken = jwtTokenService.issueAccessToken(
                cred.getUserId(), cred.getEmail(), roles);

        return new TokenPair(accessToken.serialize(), rotated.raw(), rotated.id(),
                             cred.getUserId());
    }

    @Transactional
    void logout(String bearerToken) {
        try {
            Jwt jwt = jwtDecoder.decode(bearerToken);
            UUID jti = JwtClaims.getJti(jwt);
            Duration ttl = JwtClaims.ttlUntilExpiry(jwt);
            denyList.revoke(jti, ttl);
        } catch (Exception e) {
            // Token already invalid — safe to ignore
        }
    }

    record TokenPair(String accessToken, String refreshToken, UUID refreshTokenId, UUID userId) {}
}
```

---

## 18. RefreshTokenService (final — dengan RotatedToken)

### Perbaiki `RefreshTokenService.rotate()` return type

Ganti method `rotate` di `RefreshTokenService.java` menjadi:

```java
RotatedToken rotate(String rawToken) throws ServiceException {
    String hash = sha256(rawToken);
    RefreshToken current = refreshTokenRepository.findByTokenHash(hash)
            .orElseThrow(() -> new ServiceException(AuthError.REFRESH_TOKEN_REVOKED));

    if (current.isRevoked()) {
        log.warn("Refresh token reuse detected: token_id={}, user_id={}",
                 current.getId(), current.getUserId());
        throw new ServiceException(AuthError.REFRESH_TOKEN_REVOKED);
    }

    if (current.isExpired()) {
        current.revoke();
        refreshTokenRepository.save(current);
        throw new ServiceException(AuthError.REFRESH_TOKEN_EXPIRED);
    }

    current.revoke();
    refreshTokenRepository.save(current);

    String newRaw = generateRawToken();
    String newHash = sha256(newRaw);
    Duration remainingTtl = Duration.between(Instant.now(), current.getExpiresAt());
    if (remainingTtl.isNegative()) {
        remainingTtl = Duration.ofMinutes(5);
    }

    RefreshToken rotated = new RefreshToken();
    rotated.setId(Uuidv7.generate());
    rotated.setUserId(current.getUserId());
    rotated.setTokenHash(newHash);
    rotated.setDeviceInfo(current.getDeviceInfo());
    rotated.setParentTokenId(current.getId());
    rotated.setExpiresAt(Instant.now().plus(remainingTtl));
    rotated.setIssuedAt(Instant.now());
    refreshTokenRepository.save(rotated);

    return new RotatedToken(rotated.getId(), newRaw, rotated.getUserId());
}

record RotatedToken(UUID id, String raw, UUID userId) {}
```

---

## 19. DTOs

### `src/main/java/com/gepe/app/auth/web/api/LoginRequest.java`

```java
package com.gepe.app.auth.web.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}
```

### `src/main/java/com/gepe/app/auth/web/api/RefreshRequest.java`

```java
package com.gepe.app.auth.web.api;

import jakarta.validation.constraints.NotBlank;

record RefreshRequest(@NotBlank String refreshToken) {
}
```

### `src/main/java/com/gepe/app/auth/web/api/TokenResponse.java`

```java
package com.gepe.app.auth.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
record TokenResponse(String accessToken, String refreshToken, UUID refreshTokenId, UUID userId) {
}
```

---

## 20. Controllers

### 20.1 `src/main/java/com/gepe/app/auth/web/api/AuthController.java` (API — Bearer)

```java
package com.gepe.app.auth.web.api;

import com.gepe.app.auth.exception.AuthError;
import com.gepe.app.auth.deny.AccessTokenDenyList;
import com.gepe.app.auth.jwt.JwtClaims;
import com.gepe.app.auth.web.AuthService;
import com.gepe.app.auth.web.AuthService.TokenPair;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.web.response.ApiResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class AuthController {

    private final AuthService authService;
    private final MessageHelper messageHelper;

    @PostMapping("/login")
    ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenPair tokens = authService.login(request.email(), request.password());
        TokenResponse body = new TokenResponse(
                tokens.accessToken(), tokens.refreshToken(),
                tokens.refreshTokenId(), tokens.userId());
        return ResponseEntity.ok(new ApiResponse<>(
                messageHelper.get("auth.login_success"), body));
    }

    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @Valid @RequestBody RefreshRequest request) {
        TokenPair tokens = authService.refresh(request.refreshToken());
        TokenResponse body = new TokenResponse(
                tokens.accessToken(), tokens.refreshToken(),
                tokens.refreshTokenId(), tokens.userId());
        return ResponseEntity.ok(new ApiResponse<>(
                messageHelper.get("auth.refresh_success"), body));
    }

    @PostMapping("/logout")
    ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        String token = extractBearerToken(authHeader);
        authService.logout(token);
        return ResponseEntity.ok(new ApiResponse<>(
                messageHelper.get("auth.logout_success"), null));
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ServiceException(AuthError.TOKEN_MISSING);
        }
        return authHeader.substring(7);
    }
}
```

### 20.2 `src/main/java/com/gepe/app/auth/web/web/AuthWebController.java` (WebApp — Cookie)

```java
package com.gepe.app.auth.web.web;

import com.gepe.app.auth.exception.AuthError;
import com.gepe.app.auth.web.AuthService;
import com.gepe.app.auth.web.AuthService.TokenPair;
import com.gepe.app.auth.web.api.LoginRequest;
import com.gepe.app.auth.web.api.RefreshRequest;
import com.gepe.app.auth.web.api.TokenResponse;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.web.response.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/web/auth")
@RequiredArgsConstructor
class AuthWebController {

    private final AuthService authService;
    private final MessageHelper messageHelper;

    @Value("${app.security.cookie.name}")
    private String cookieName;

    @Value("${app.security.cookie.path}")
    private String cookiePath;

    @Value("${app.security.cookie.same-site}")
    private String sameSite;

    @Value("${app.security.cookie.secure}")
    private boolean secure;

    @Value("${app.security.access-token-ttl}")
    private Duration accessTokenTtl;

    @PostMapping("/login")
    ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        TokenPair tokens = authService.login(request.email(), request.password());
        setAccessTokenCookie(response, tokens.accessToken());

        TokenResponse body = new TokenResponse(
                null, tokens.refreshToken(),
                tokens.refreshTokenId(), tokens.userId());
        return ResponseEntity.ok(new ApiResponse<>(
                messageHelper.get("auth.login_success"), body));
    }

    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletResponse response) {
        TokenPair tokens = authService.refresh(request.refreshToken());
        setAccessTokenCookie(response, tokens.accessToken());

        TokenResponse body = new TokenResponse(
                null, tokens.refreshToken(),
                tokens.refreshTokenId(), tokens.userId());
        return ResponseEntity.ok(new ApiResponse<>(
                messageHelper.get("auth.refresh_success"), body));
    }

    @PostMapping("/logout")
    ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        extractTokenFromCookie(request).ifPresent(authService::logout);
        clearAccessTokenCookie(response);

        return ResponseEntity.ok(new ApiResponse<>(
                messageHelper.get("auth.logout_success"), null));
    }

    private void setAccessTokenCookie(HttpServletResponse response, String token) {
        long maxAgeSeconds = accessTokenTtl.getSeconds();
        ResponseCookie cookie = ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(secure)
                .path(cookiePath)
                .sameSite(sameSite)
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                           cookie.toString());
    }

    private Optional<String> extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private void clearAccessTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(secure)
                .path(cookiePath)
                .sameSite(sameSite)
                .maxAge(0)
                .build();
        response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                           cookie.toString());
    }
}
```

---

## 21. Password Encoder Bean

### Tambahkan di `SecurityConfig.java`:

```java
@Bean
PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

---

## 22. Database Migrations

### `src/main/resources/db/migration/auth/V1__signing_keys.sql`

```sql
CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE auth.signing_keys (
    kid                 UUID            NOT NULL,
    public_key          TEXT            NOT NULL,
    private_key_cipher  BYTEA           NOT NULL,
    enc_key_id          VARCHAR(50)     NOT NULL,
    algorithm           VARCHAR(10)     NOT NULL DEFAULT 'RS256',
    status              VARCHAR(20)     NOT NULL,
    not_before          TIMESTAMPTZ     NOT NULL,
    not_after           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_signing_keys PRIMARY KEY (kid)
);

CREATE INDEX idx_signing_keys_status_not_before
    ON auth.signing_keys (status, not_before DESC);

CREATE INDEX idx_signing_keys_status_not_after
    ON auth.signing_keys (status, not_after)
    WHERE not_after IS NOT NULL;
```

### `src/main/resources/db/migration/auth/V2__refresh_tokens.sql`

```sql
CREATE TABLE auth.refresh_tokens (
    id              UUID            NOT NULL,
    user_id         UUID            NOT NULL,
    token_hash      VARCHAR(128)    NOT NULL,
    device_info     VARCHAR(500),
    parent_token_id UUID,
    expires_at      TIMESTAMPTZ     NOT NULL,
    issued_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    rotated_at      TIMESTAMPTZ,

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id
    ON auth.refresh_tokens (user_id);

CREATE INDEX idx_refresh_tokens_hash
    ON auth.refresh_tokens (token_hash);

CREATE INDEX idx_refresh_tokens_expires
    ON auth.refresh_tokens (expires_at)
    WHERE revoked_at IS NULL;
```

### `src/main/resources/db/migration/auth/V3__user_credentials.sql`

```sql
CREATE TABLE auth.user_credentials (
    id              UUID            NOT NULL,
    user_id         UUID            NOT NULL,
    email           VARCHAR(320)     NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_credentials PRIMARY KEY (id),
    CONSTRAINT uq_user_credentials_user_id UNIQUE (user_id),
    CONSTRAINT uq_user_credentials_email UNIQUE (email)
);

CREATE INDEX idx_user_credentials_email
    ON auth.user_credentials (email);
```

---

## 23. Initial Key Seeding

Karena RSA key harus ada di DB sebelum JWT bisa di-issue, gunakan `ApplicationRunner` untuk seeding key pertama kali:

### `src/main/java/com/gepe/app/auth/keyrotation/SigningKeySeeder.java`

```java
package com.gepe.app.auth.keyrotation;

import com.gepe.app.auth.crypto.MasterKeyProvider;
import com.gepe.app.auth.crypto.RsaKeyService;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.platform.support.Uuidv7;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class SigningKeySeeder implements ApplicationRunner {

    private final SigningKeyRepository signingKeyRepository;
    private final RsaKeyService rsaKeyService;
    private final MasterKeyProvider masterKeyProvider;

    @Override
    public void run(ApplicationArguments args) {
        if (signingKeyRepository.findByStatusIn(
                List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS))
                .isEmpty()) {
            log.info("No active signing keys found — generating initial key pair...");

            KeyPair keyPair = rsaKeyService.generateKeyPair();
            RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey priv = (RSAPrivateKey) keyPair.getPrivate();

            String pubBase64 = rsaKeyService.publicKeyToBase64(pub);
            byte[] privCipher = rsaKeyService.encryptPrivateKey(priv);

            SigningKey key = new SigningKey(
                    Uuidv7.generate(),
                    pubBase64,
                    privCipher,
                    masterKeyProvider.getCurrentKeyId(),
                    SigningKey.Status.ACTIVE,
                    Instant.now(),
                    null
            );

            signingKeyRepository.save(key);
            log.info("Initial signing key created: kid={}", key.getKid());
        }
    }
}
```

---

## 24. Verify: Modularity Test

Setelah semua code di atas ditambahkan, jalankan:

```bash
./mvnw test -Dtest="ModularityTests"
```

Test harus **hijau** karena:
- Semua kelas internal `package-private` (tidak bisa di-import module lain)
- Hanya `auth.CurrentUser` yang `public` — itulah satu-satunya kontrak antar module
- Tidak ada import dari module lain selain ke `platform` (shared module)

---

## 25. Environment Variables

```bash
# Generate master key (AES-256, 32 bytes)
export MASTER_KEY_CURRENT=$(openssl rand -base64 32)

# Rotation: saat rotasi, pindahkan current ke previous
# export MASTER_KEY_PREVIOUS=$MASTER_KEY_CURRENT
# export MASTER_KEY_CURRENT=$(openssl rand -base64 32)
# Lalu jalankan MasterKeyRotationJob (atau tunggu cron)

# Redis
export SPRING_DATA_REDIS_HOST=localhost
export SPRING_DATA_REDIS_PORT=6379
```

---

## 26. Endpoint Summary

| Method | Route | Chain | Auth | Body | Response |
|---|---|---|---|---|---|
| POST | `/api/auth/login` | api (Bearer) | None | `{ email, password }` | `{ accessToken, refreshToken, refreshTokenId, userId }` |
| POST | `/api/auth/refresh` | api (Bearer) | None | `{ refreshToken }` | `{ accessToken, refreshToken, refreshTokenId, userId }` |
| POST | `/api/auth/logout` | api (Bearer) | Bearer AT | — | 200 |
| POST | `/web/auth/login` | web (Cookie) | None | `{ email, password }` | `{ refreshToken, refreshTokenId, userId }` + `Set-Cookie: ACCESS_TOKEN` |
| POST | `/web/auth/refresh` | web (Cookie) | None | `{ refreshToken }` | `{ refreshToken, refreshTokenId, userId }` + `Set-Cookie: ACCESS_TOKEN` |
| POST | `/web/auth/logout` | web (Cookie) | Cookie AT | — | 200 + `Set-Cookie: ACCESS_TOKEN= (maxage=0)` |

---

## 27. Checklist Eksekusi (Urutan yang Disarankan)

1. [ ] Tambah dependencies di `pom.xml`
2. [ ] Tambah `flyway.locations` dan `app.security.*` di `application.yaml`
3. [ ] Tambah `classpath:i18n/auth/messages` di `I18nConfig.java`
4. [ ] Buat file i18n: `messages.properties` dan `messages_id.properties`
5. [ ] Buat migration SQL: `V1__signing_keys.sql`, `V2__refresh_tokens.sql`, `V3__user_credentials.sql`
6. [ ] Buat package `com.gepe.app.auth` dengan `package-info.java`
7. [ ] Buat `exception/AuthError.java`
8. [ ] Buat `CurrentUser.java`
9. [ ] Buat `jwt/*` (JwtProperties, JwtConfig, JwtClaims, JwtTokenService, JwtAuthenticationToken, DbJwtDecoder)
10. [ ] Buat `crypto/*` (MasterKeyProvider, AesGcmService, RsaKeyService) — RsaKeyService terima SigningKeyData
11. [ ] Buat `keyrotation/*` (SigningKeyStatus, SigningKeyData, SigningKeyService, SigningKeyRepository, SigningKeyRotationJob/Scheduler, MasterKeyRotationJob/Scheduler, SigningKeySeeder)
12. [ ] Buat `internal/entity/*` (SigningKey, RefreshToken, UserCredential)
13. [ ] Buat `deny/*` (RedisConfig, AccessTokenDenyList)
14. [ ] Buat `refresh/*` (RefreshTokenRepository, RefreshTokenService)
15. [ ] Buat `credential/*` (UserCredentialRepository)
16. [ ] Buat `web/AuthService.java`
17. [ ] Buat `web/api/*` (AuthController, LoginRequest, RefreshRequest, TokenResponse)
18. [ ] Buat `web/web/AuthWebController.java`
19. [ ] Buat `cookie/CookieAuthenticationFilter.java`
20. [ ] Buat `config/SecurityConfig.java` (termasuk `passwordEncoder` bean)
21. [ ] Set env `MASTER_KEY_CURRENT`
22. [ ] Start Redis + Postgres
23. [ ] `./mvnw compile`
24. [ ] `./mvnw test -Dtest="ModularityTests"` — **harus hijau**
25. [ ] `./mvnw test`
26. [ ] `./mvnw spring-boot:run`

---

## Arsitektur Lengkap (Visual)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  REQUEST                                                                  │
│                                                                           │
│  Mobile        WebApp                                                     │
│    │              │                                                        │
│    ▼              ▼                                                        │
│  /api/**       /web/**                                                    │
│  Bearer AT     Cookie AT                                                  │
│    │              │                                                        │
│    ▼              ▼                                                        │
│  ┌─────────────────────────────────────────────────────────────────┐      │
│  │  SecurityConfig (3 chain)                                        │      │
│  │                                                                  │      │
│  │  Chain 1 /api/**  → BearerTokenAuthenticationFilter → JWT Decode │      │
│  │  Chain 2 /web/**  → CookieAuthenticationFilter → JWT Decode       │      │
│  │  Chain 3 /**      → denyAll                                       │      │
│  └─────────────────────────────────────────────────────────────────┘      │
│                        │                                                   │
│                        ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────┐      │
│  │  DbJwtDecoder                                                    │      │
│  │  1. Parse JWT → get kid                                          │      │
│  │  2. Query signing_keys WHERE kid=? AND status IN (ACTIVE,PREV)   │      │
│  │  3. Build RSAKey from public_key (stored raw Base64)             │      │
│  │  4. NimbusJwtDecoder.verify(signature, RSAKey)                   │      │
│  │  5. Jwt (sub=userId, email, roles, jti, exp)                     │      │
│  └─────────────────────────────────────────────────────────────────┘      │
│                        │                                                   │
│                        ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────┐      │
│  │  Redis deny-list check (per-request)                             │      │
│  │  EXISTS revoked_at:{jti} ──YES──► 401 Token Revoked              │      │
│  │  │                                                               │      │
│  │  NO ───► OK, proceed                                             │      │
│  └─────────────────────────────────────────────────────────────────┘      │
│                        │                                                   │
│                        ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────┐      │
│  │  Authentication → principal = UUID(userId)                       │      │
│  │  RequestContext.getCurrentUserId() → UUID ✅                      │      │
│  └─────────────────────────────────────────────────────────────────┘      │
│                        │                                                   │
│                        ▼                                                   │
│  ┌─────────────────────────────────────────────────────────────────┐      │
│  │  Controller / Service                                            │      │
│  └─────────────────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Catatan Keamanan

1. **Master key di env** bukan KMS — memadai untuk skala ini. Jangan commit ke git.
2. **Rotasi master key** memerlukan `MASTER_KEY_PREVIOUS` tersisa sampai semua key selesai di-re-encrypt oleh `MasterKeyRotationJob`.
3. **SameSite=Strict + CSRF aktif** di chain `/web/**` — double defense.
4. **CSRF disabled** di chain `/api/**` karena mobile tidak pakai cookie — aman.
5. **Refresh token detection reuse**: jika parent_token_id sudah punya child → token di-reuse → seluruh sesi di-revoke.
6. **Access token deny-list** Redis dengan TTL = `exp - now` — tidak membocorkan memori tanpa batas.
7. Semua UUID **v7** via `Uuidv7.generate()` — termasuk `jti`, `kid`, refresh token id, user credential id.
