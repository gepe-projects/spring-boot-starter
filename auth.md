# Auth Module — Implementation Guide

> Self-contained JWT auth (RSA-signed, AES-256-GCM encrypted keys at rest, multi-identity login).
> Satu mekanisme token, dua jalur: **Bearer header** (mobile/API) + **cookie** (webapp).
> Semua stateless (JWT tetap valid sampai expiry walau logout — **tanpa deny-list Redis**).
> Principal = `UUID` — kompatibel dengan `RequestContext.getCurrentUserId()`.
>
> **Kepatuhan arsitektur:** dokumen ini mengikuti `AGENTS.md` — `internal/` **mandatory**, `api/` khusus public
> contract, controller di `internal/delivery/http`, DTO boundary-safe di `internal/dto/`, Quartz `Job` dipisah dari
> `*Scheduler`, dan **cursor/keyset pagination** adalah satu-satunya strategi pagination (AGENTS.md §4).

---

## Arsitektur Ringkas

```
                    ┌──────────────────────────────────────┐
                    │           Spring Security             │
                    │                                      │
  Mobile ───►  Chain 1  /api/**  Bearer → JWT → UUID       │
  WebApp ───►  Chain 2  /web/**  Cookie → JWT → UUID       │
                    │        + oauth2Login (Google)        │
  Lain    ───►  Chain 3  catch-all → deny                  │
                    │                                      │
                    ▼                                      │
              JWT Decoder (DbJwtDecoder → signing_keys)     │
              Refresh token (session) → refresh_tokens      │
              (AT stateless: valid sampai exp, tanpa deny)  │
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
| Refresh token | **Opaque, di-store DB** = representasi **session** (revocable) |
| Session | 1 refresh token aktif = 1 session; `session_id` stabil antar rotasi |
| Logout | **Hanya revoke session (refresh token)**. AT tetap valid sampai expiry — **tanpa deny-list Redis** (toleransi: hindari network hop) |
| Signing key | **Tepat 1 `ACTIVE`** di DB (di-enforce partial unique index). Key lama → `PREVIOUS` (verifikasi AT lama via KID di payload) → `RETIRED` |
| Private key at-rest | Encode AES-256-GCM sebelum masuk `signing_keys` |
| Master key | Env `MASTER_KEY_CURRENT` + `MASTER_KEY_PREVIOUS` (dual) |
| Cookie | `HttpOnly`, `Secure`, `SameSite=Strict` |
| Login | **Credentials** (email+password) **atau OAuth Google** |
| Email verified | `users.email_verified_at` — credentials → `NULL`; google → `now()` (google sudah verify) |
| Account linking | Satu `users` kanonik, banyak `auth_identities`. Binding by **email** (creds↔google) |
| OAuth dance | **Di backend** (redirect flow) untuk web; **mobile ditunda** (frontend nanti) |
| Pagination | **Cursor/keyset wajib** (AGENTS.md §4) — daftar session pakai keyset |

---

## Daftar File yang Dibuat / Diubah

### Struktur (per AGENTS.md)

```
src/main/java/com/gepe/app/
├── AuthApplication.java                        ← @Modulith(sharedModules = "platform")
├── auth/
│   ├── api/                                    ← ONLY public types (inter-module contract)
│   │   ├── AuthApi.java                        ← public interface (SYNC calls)
│   │   ├── CurrentUser.java                    ← public DTO (userId, email, emailVerified)
│   │   ├── UserAuthenticated.java              ← public event (login sukses)
│   │   └── UserRegistered.java                 ← public event (user baru dibuat)
│   └── internal/                               ← MANDATORY. Semua implementasi. Package-private by default.
│       ├── entity/                             ← JPA entities, schema = "auth"
│       │   ├── User.java
│       │   ├── AuthIdentity.java
│       │   ├── RefreshToken.java
│       │   └── SigningKey.java
│       ├── repository/                         ← Spring Data JPA (return entities — internal only)
│       │   ├── UserRepository.java
│       │   ├── AuthIdentityRepository.java
│       │   ├── RefreshTokenRepository.java
│       │   └── SigningKeyRepository.java
│       ├── service/                            ← use-case orchestration; entity → DTO di boundary;
│       │   │                                      publish event via ApplicationEventPublisher
│       │   ├── AuthService.java
│       │   ├── AuthApiImpl.java                ← implementasi public AuthApi
│       │   ├── SessionService.java             ← list/revoke session (cursor paginated)
│       │   ├── SigningKeyService.java
│       │   └── RefreshTokenService.java
│       ├── jwt/                                ← token infrastructure
│       │   ├── JwtProperties.java              ← @ConfigurationProperties
│       │   ├── JwtConfig.java
│       │   ├── JwtService.java
│       │   ├── JwtClaims.java
│       │   ├── JwtAuthenticationToken.java
│       │   └── DbJwtDecoder.java
│       ├── crypto/                             ← cryptography
│       │   ├── PasswordHasher.java             ← membungkus PasswordEncoder
│       │   ├── MasterKeyProvider.java
│       │   ├── AesGcmService.java
│       │   └── RsaKeyService.java
│       ├── oauth/                              ← Google backend OAuth (web only)
│       │   ├── GoogleOAuth2UserService.java    ← map profile Google → AuthIdentity
│       │   └── GoogleAuthSuccessHandler.java   ← create/link user + set cookie + redirect
│       ├── cookie/                             ← cookie auth filter (chain /web/**)
│       │   └── CookieAuthenticationFilter.java
│       ├── job/                                ← Quartz Job (pekerjaan) + *Scheduler (jadwal) — selalu dipisah
│       │   ├── SigningKeyRotationJob.java
│       │   ├── SigningKeyRotationScheduler.java
│       │   ├── MasterKeyRotationJob.java
│       │   ├── MasterKeyRotationScheduler.java
│       │   └── SigningKeySeeder.java           ← ApplicationRunner (bootstrap awal)
│       ├── dto/                                ← boundary-safe records (service ↔ delivery/http)
│       │   ├── SigningKeyData.java
│       │   ├── SigningKeyStatus.java
│       │   ├── LoginRequest.java
│       │   ├── RefreshRequest.java
│       │   ├── LogoutRequest.java
│       │   ├── SetPasswordRequest.java
│       │   ├── TokenResponse.java
│       │   ├── SessionInfo.java
│       │   ├── SessionPage.java
│       │   ├── TokenWithId.java
│       │   └── RotatedToken.java
│       ├── exception/
│       │   └── AuthError.java
│       └── config/
│           ├── AuthSecurityConfig.java         ← 3 SecurityFilterChain + oauth2Login + PasswordEncoder
│           └── OAuthConfig.java                ← ClientRegistrationRepository (google)
└── platform/                                   ← shared infra (tidak berubah)
```

### Kebijakan visibilitas (penting)

1. `api/` → **selalu `public`** — ini satu-satunya kontrak antar modul.
2. `internal/**` → **`package-private` secara default**. Jenis yang harus dirujuk **lintas sub-package**
   (orchestrasi service, injeksi DI antar sub-package, dan controller di `internal/delivery/http`) dinyatakan `public` —
   ini persis pengecualian *"NEVER public unless absolutely unavoidable"* di AGENTS.md §2.2.
   Contoh: `AuthService` dipakai `AuthController` (sub-package berbeda) → `public`; `SigningKeyRepository` dipakai
   `SigningKeyService` (sub-package berbeda) → `public`.
3. Yang tetap `package-private`: helper lokal yang tidak keluar dari sub-package-nya sendiri
   (mis. `JwtConfig`, `OAuthConfig`, `JwtClaims` bila hanya dipakai dalam `internal/jwt`).
4. Batas antar **modul** tetap di-enforce oleh `ModularityTests` — `internal/**` dari modul lain
   **tidak boleh di-import** (lihat §Verify).

> Intinya: `internal/` menjaga satu unit implementasi tetap rapat secara *paket*, dan `ModularityTests`
> yang menjaga agar modul lain tidak bisa menyentuhnya — dua lapis pertahanan yang saling melengkapi.

### File existing (diubah — **jelas didokumentasikan**)

| File | Perubahan |
|---|---|
| `pom.xml` | (sudah) `spring-boot-starter-security-oauth2-client`, `spring-boot-starter-oauth2-resource-server`. **Tidak** perlu `spring-boot-starter-data-redis` untuk deny-list |
| `application.yaml` | Tambah `spring.flyway.locations`, `app.security.*` (**tanpa** `deny-list-prefix`), `spring.security.oauth2.client.*` (google), redis config |
| `I18nConfig.java` | Tambah basename `classpath:i18n/auth/messages` |
| `src/test/java/.../ModularityTests.java` | Tidak perlu diubah (test tetap hijau selama aturan modulith terpenuhi) |

---

## 1. Dependencies (`pom.xml`)

```xml
<!-- Auth: OAuth2 Resource Server (JWT) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>

<!-- Auth: OAuth2 Client (Google backend OAuth — web only) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-client</artifactId>
</dependency>
```

> **Deny-list Redis DIHAPUS** — AT stateless ditoleransi sampai expiry, sehingga `spring-boot-starter-data-redis`
> **tidak** diperlukan untuk auth. Bila Redis tetap terpasang untuk shared cache modul lain, config dasarnya boleh ada
> tapi jangan dipakai deny-list.

---

## 2. Configuration (`application.yaml`)

```yaml
spring:
  flyway:
    locations:
      - classpath:db/migration
      - classpath:db/migration/auth
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID:}
            client-secret: ${GOOGLE_CLIENT_SECRET:}
            scope:
              - openid
              - profile
              - email
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
        provider:
          google:
            issuer-uri: https://accounts.google.com
  data:
    redis:
      host: localhost
      port: 6379

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
```

> **Tidak ada** `deny-list-prefix` — deny-list sudah dibuang dari desain.

---

## 3. I18n Config — ubah `I18nConfig.java`

**File:** `src/main/java/com/gepe/app/platform/config/i18n/I18nConfig.java`

```java
source.setBasenames(
    "classpath:i18n/messages/messages",
    "classpath:i18n/auth/messages"
);
```

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

> Hanya jenis di bawah ini yang boleh di-import modul lain. Tidak ada `public` lain di luar `api/`.

### 5.1 `src/main/java/com/gepe/app/auth/api/CurrentUser.java`

```java
package com.gepe.app.auth.api;

import java.util.UUID;

public record CurrentUser(UUID userId, String email, boolean emailVerified) {}
```

### 5.2 `src/main/java/com/gepe/app/auth/api/AuthApi.java`

```java
package com.gepe.app.auth.api;

import java.util.Optional;
import java.util.UUID;

/** Synchronous contract untuk modul lain (prioritas 2 di AGENTS.md §5). */
public interface AuthApi {

    Optional<CurrentUser> findByUserId(UUID userId);

    Optional<CurrentUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
```

### 5.3 `src/main/java/com/gepe/app/auth/api/UserAuthenticated.java`

```java
package com.gepe.app.auth.api;

import java.util.UUID;

/** Dipublish setelah login sukses (AGENTS.md §5 — async side-effect). */
public record UserAuthenticated(UUID userId, String email) {}
```

### 5.4 `src/main/java/com/gepe/app/auth/api/UserRegistered.java`

```java
package com.gepe.app.auth.api;

import java.util.UUID;

/** Dipublish saat user BARU pertama kali dibuat (login pertama, creds atau google). */
public record UserRegistered(UUID userId, String email) {}
```

> **Pola event wajib (AGENTS.md §5):** dipublish dari `internal/service/` (satu-satunya tempat boleh
> `ApplicationEventPublisher.publishEvent(...)`), dikonsumsi modul lain dengan `@ApplicationModuleListener`
> (bukan `@EventListener`), dan listener **harus idempotent**.

### 5.5 Implementasi AuthApi — `internal/service/AuthApiImpl.java`

```java
package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.AuthApi;
import com.gepe.app.auth.api.CurrentUser;
import com.gepe.app.auth.internal.entity.User;
import com.gepe.app.auth.internal.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthApiImpl implements AuthApi {

    private final UserRepository userRepository;

    @Override
    public Optional<CurrentUser> findByUserId(UUID userId) {
        return userRepository.findById(userId).map(this::toCurrentUser);
    }

    @Override
    public Optional<CurrentUser> findByEmail(String email) {
        return userRepository.findByEmail(email).map(this::toCurrentUser);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    private CurrentUser toCurrentUser(User u) {
        return new CurrentUser(u.getId(), u.getEmail(), u.getEmailVerifiedAt() != null);
    }
}
```

> **DTO over entity di boundary (AGENTS.md §3.6):** entity `User` tidak pernah bocor keluar modul — dikonversi ke
> `CurrentUser` di sini.

---

## 6. Error Codes (Module-Specific)

> **PENTING — split 2 jalur error.** `AuthError` + `ServiceException` **hanya** untuk jalur service/controller
> (di-handle oleh `GlobalExceptionHandler` → respons i18n). Di dalam Spring Security filter chain
> (`DbJwtDecoder`, `JwtClaims` saat validasi token) jangan lempar `ServiceException` — hasilnya **500**, bukan 401.
> Pakai `JwtException` / `BadCredentialsException` agar Spring mengembalikan **401 secara otomatis**.

### `src/main/java/com/gepe/app/auth/internal/exception/AuthError.java`

```java
package com.gepe.app.auth.internal.exception;

import com.gepe.app.platform.exception.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum AuthError implements ErrorCode {

    // ── credentials ──
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "auth.invalid_credentials"),

    // ── access token ──
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "auth.token_expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "auth.token_invalid"),
    TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "auth.token_revoked"),
    TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "auth.token_missing"),
    TOKEN_INVALID_CLAIM(HttpStatus.UNAUTHORIZED, "auth.token_invalid_claim"),

    // ── refresh token / session ──
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "auth.refresh_token_expired"),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "auth.refresh_token_revoked"),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "auth.refresh_token_reused"),
    CURRENT_TOKEN_REQUIRED(HttpStatus.BAD_REQUEST, "auth.current_token_required"),
    CANNOT_REVOKE_CURRENT(HttpStatus.BAD_REQUEST, "auth.cannot_revoke_current"),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "auth.session_not_found"),

    // ── identity / linking ──
    EMAIL_ALREADY_LINKED(HttpStatus.CONFLICT, "auth.email_already_linked"),
    IDENTITY_EXISTS(HttpStatus.CONFLICT, "auth.identity_exists"),
    IDENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "auth.identity_not_found"),

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
auth.current_token_required=Current refresh token is required
auth.cannot_revoke_current=You cannot revoke the current session
auth.session_not_found=Session not found
auth.email_already_linked=This email is already linked to another account
auth.identity_exists=Identity already exists for this account
auth.identity_not_found=Identity not found
auth.key_generation_failed=Failed to generate signing key pair
auth.key_not_found=Signing key not found
auth.encryption_failed=Failed to encrypt private key
auth.decryption_failed=Failed to decrypt private key
auth.master_key_invalid=Master encryption key is invalid or missing
auth.login_success=Login successful
auth.refresh_success=Token refreshed successfully
auth.logout_success=Logout successful
auth.password_set_success=Password set successfully
auth.sessions_revoked_success=Selected sessions revoked
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
auth.current_token_required=Refresh token saat ini wajib dikirim
auth.cannot_revoke_current=Tidak bisa mencabut session yang sedang dipakai
auth.session_not_found=Session tidak ditemukan
auth.email_already_linked=Email ini sudah tertaut ke akun lain
auth.identity_exists=Identitas sudah ada untuk akun ini
auth.identity_not_found=Identitas tidak ditemukan
auth.key_generation_failed=Gagal menghasilkan pasangan kunci penandatanganan
auth.key_not_found=Kunci penandatanganan tidak ditemukan
auth.encryption_failed=Gagal mengenkripsi kunci privat
auth.decryption_failed=Gagal mendekripsi kunci privat
auth.master_key_invalid=Kunci enkripsi master tidak valid atau tidak ditemukan
auth.login_success=Login berhasil
auth.refresh_success=Token berhasil diperbarui
auth.logout_success=Logout berhasil
auth.password_set_success=Password berhasil diatur
auth.sessions_revoked_success=Session terpilih telah dicabut
```

---

## 8. Security Configuration

### `src/main/java/com/gepe/app/auth/internal/config/AuthSecurityConfig.java`

```java
package com.gepe.app.auth.internal.config;

import com.gepe.app.auth.internal.cookie.CookieAuthenticationFilter;
import com.gepe.app.auth.internal.oauth.GoogleAuthSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
class AuthSecurityConfig {

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    JwtAuthenticationProvider jwtAuthenticationProvider(JwtDecoder jwtDecoder) {
        return new JwtAuthenticationProvider(jwtDecoder);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // ──────────────────────────────────────────────
    // Chain 1: /api/** → Bearer (Mobile/API)
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
    // Chain 2: /web/** → Cookie (WebApp) + Google OAuth
    // ──────────────────────────────────────────────
    @Bean
    @Order(2)
    SecurityFilterChain web(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            CookieAuthenticationFilter cookieFilter,
            GoogleAuthSuccessHandler googleAuthSuccessHandler) throws Exception {
        http.securityMatcher("/web/**", "/oauth2/**", "/login/oauth2/**");
        http.addFilterBefore(cookieFilter, UsernamePasswordAuthenticationFilter.class);
        http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwtDecoder)));
        http.oauth2Login(o -> o.successHandler(googleAuthSuccessHandler));
        http.csrf(AbstractHttpConfigurer::disable);
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

> **OAuth dance di backend:** `spring-boot-starter-oauth2-client` menyediakan endpoint initiate
> (`/oauth2/authorization/google`) dan callback (`/login/oauth2/code/google`) — keduanya berjalan **di server**.
> `GoogleAuthSuccessHandler` menerima profil Google, melakukan create/link user, lalu menerbitkan JWT (cookie) dan
> redirect ke webapp. **Tidak ada OAuth di frontend** untuk web. **Mobile ditunda.**

### `src/main/java/com/gepe/app/auth/internal/config/OAuthConfig.java`

```java
package com.gepe.app.auth.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** ClientRegistrationRepository (google) di-auto-configure dari spring.security.oauth2.client.* */
@Configuration(proxyBeanMethods = false)
class OAuthConfig {

    // jika perlu customisasi:
    // @Bean
    // ClientRegistrationRepository clientRegistrationRepository(
    //         ClientRegistrations properties) {
    //     return new InMemoryClientRegistrationRepository(properties.getRegistrations());
    // }
}
```

---

## 9. JWT Infrastructure

### 9.1 `src/main/java/com/gepe/app/auth/internal/jwt/JwtProperties.java`

```java
package com.gepe.app.auth.internal.jwt;

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

        CookieProperties cookie
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

### 9.2 `src/main/java/com/gepe/app/auth/internal/jwt/JwtConfig.java`

```java
package com.gepe.app.auth.internal.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {
}
```

### 9.3 `src/main/java/com/gepe/app/auth/internal/jwt/JwtClaims.java`

> **Jalur error**: security-chain — pakai `JwtException` (bukan `ServiceException`) → 401 otomatis.

```java
package com.gepe.app.auth.internal.jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtException;

public final class JwtClaims {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLES = "roles";

    private JwtClaims() {}

    public static UUID getUserId(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        String sub = jwt.getClaimAsString(JwtClaimNames.SUB);
        if (sub == null || sub.isBlank()) throw new JwtException("Missing sub claim");
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Invalid sub claim: " + sub);
        }
    }

    public static String getEmail(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        String email = jwt.getClaimAsString(CLAIM_EMAIL);
        if (email == null) throw new JwtException("Missing email claim");
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
        if (id == null || id.isBlank()) throw new JwtException("Missing jti claim");
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Invalid jti claim: " + id);
        }
    }

    public static Instant getExpiresAt(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) throw new JwtException("Missing exp claim");
        return expiresAt;
    }

    public static Duration ttlUntilExpiry(Jwt jwt) {
        Instant expiresAt = getExpiresAt(jwt);
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
```

### 9.4 `src/main/java/com/gepe/app/auth/internal/jwt/JwtService.java`

```java
package com.gepe.app.auth.internal.jwt;

import static com.gepe.app.auth.internal.jwt.JwtClaims.CLAIM_EMAIL;
import static com.gepe.app.auth.internal.jwt.JwtClaims.CLAIM_ROLES;

import com.gepe.app.auth.internal.crypto.RsaKeyService;
import com.gepe.app.auth.internal.dto.SigningKeyData;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.service.SigningKeyService;
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
public class JwtService {

    private final SigningKeyService signingKeyService;
    private final RsaKeyService rsaKeyService;
    private final JwtProperties properties;

    public SignedJWT issueAccessToken(UUID userId, String email, List<String> roles) {
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

> `SigningKeyData` adalah DTO dari `internal/dto` — bukan entity langsung (AGENTS.md §3.6).

### 9.5 `src/main/java/com/gepe/app/auth/internal/jwt/JwtAuthenticationToken.java`

```java
package com.gepe.app.auth.internal.jwt;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID userId;
    private final Jwt jwt;

    JwtAuthenticationToken(
            UUID userId,
            Jwt jwt,
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

### 9.6 `src/main/java/com/gepe/app/auth/internal/jwt/DbJwtDecoder.java`

```java
package com.gepe.app.auth.internal.jwt;

import com.gepe.app.auth.internal.dto.SigningKeyData;
import com.gepe.app.auth.internal.service.SigningKeyService;
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

> **Kunci rotasi:** decoder mengambil **ACTIVE + PREVIOUS**. AT lama yang masih berlaku tetap bisa diverifikasi karena
> `kid` di payload menunjuk key lama yang statusnya PREVIOUS (masih ada di DB). Hanya **1** ACTIVE yang boleh ada.

---

## 10. Cookie Authentication Filter

### `src/main/java/com/gepe/app/auth/internal/cookie/CookieAuthenticationFilter.java`

```java
package com.gepe.app.auth.internal.cookie;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
public class CookieAuthenticationFilter extends OncePerRequestFilter {

    private final AuthenticationManager authenticationManager;
    private final String cookieName;

    public CookieAuthenticationFilter(
            AuthenticationManager authenticationManager,
            @Value("${app.security.cookie.name:ACCESS_TOKEN}") String cookieName) {
        this.authenticationManager = authenticationManager;
        this.cookieName = cookieName;
    }

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
                .filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
```

---

## 11. Encryption / Cryptography

### 11.1 `src/main/java/com/gepe/app/auth/internal/crypto/MasterKeyProvider.java`

```java
package com.gepe.app.auth.internal.crypto;

import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.platform.exception.ServiceException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MasterKeyProvider {

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

    public SecretKey getCurrent() {
        SecretKey key = keys.get(KEY_ID_CURRENT);
        if (key == null) {
            throw new ServiceException(AuthError.MASTER_KEY_INVALID);
        }
        return key;
    }

    public SecretKey getById(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new ServiceException(AuthError.MASTER_KEY_INVALID);
        }
        return key;
    }

    public boolean hasPrevious() {
        return keys.containsKey(KEY_ID_PREVIOUS);
    }

    public String getCurrentKeyId() {
        return KEY_ID_CURRENT;
    }

    public String getPreviousKeyId() {
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

### 11.2 `src/main/java/com/gepe/app/auth/internal/crypto/AesGcmService.java`

```java
package com.gepe.app.auth.internal.crypto;

import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.platform.exception.ServiceException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AesGcmService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final MasterKeyProvider masterKeyProvider;

    public byte[] encrypt(byte[] plaintext) {
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
            throw new ServiceException(AuthError.ENCRYPTION_FAILED);
        }
    }

    public byte[] decrypt(byte[] ciphertext, String keyId) {
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
            throw new ServiceException(AuthError.DECRYPTION_FAILED);
        }
    }
}
```

### 11.3 `src/main/java/com/gepe/app/auth/internal/crypto/RsaKeyService.java`

```java
package com.gepe.app.auth.internal.crypto;

import com.gepe.app.auth.internal.dto.SigningKeyData;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.platform.exception.ServiceException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RsaKeyService {

    private static final int RSA_KEY_SIZE = 2048;

    private final AesGcmService aesGcmService;

    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(RSA_KEY_SIZE, new SecureRandom());
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new ServiceException(AuthError.KEY_GENERATION_FAILED);
        }
    }

    public String publicKeyToBase64(RSAPublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public byte[] encryptPrivateKey(RSAPrivateKey privateKey) {
        return aesGcmService.encrypt(privateKey.getEncoded());
    }

    public RSAPrivateKey decryptPrivateKey(SigningKeyData signingKey) {
        byte[] pkcs8 = aesGcmService.decrypt(
                signingKey.privateKeyCipher(),
                signingKey.encKeyId());
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new ServiceException(AuthError.DECRYPTION_FAILED);
        }
    }

    public RSAPublicKey parsePublicKey(String base64PublicKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new ServiceException(AuthError.KEY_NOT_FOUND);
        }
    }
}
```

### 11.4 `src/main/java/com/gepe/app/auth/internal/crypto/PasswordHasher.java`

```java
package com.gepe.app.auth.internal.crypto;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PasswordHasher {

    private final PasswordEncoder passwordEncoder;

    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encoded) {
        return passwordEncoder.matches(rawPassword, encoded);
    }
}
```

---

## 12. Users & Identities — model & account linking

> **Satu `users` kanonik + banyak `auth_identities`.** Email kanonik tinggal di `users`; setiap metode login
> (credentials/google) adalah satu baris `auth_identities` yang menunjuk ke user yang sama. Ini memungkinkan
> **account linking**: creds ↔ google untuk email yang sama.

### 12.1 `src/main/java/com/gepe/app/auth/internal/entity/User.java`

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
@Table(name = "users", schema = "auth")
@Getter
@Setter
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;   // NULL = belum verified; isi = verified

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected User() {}

    public User(String email, Instant emailVerifiedAt) {
        this.email = email;
        this.emailVerifiedAt = emailVerifiedAt;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = Uuidv7.generate();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    public void markEmailVerified() {
        if (emailVerifiedAt == null) emailVerifiedAt = Instant.now();
    }
}
```

### 12.2 `src/main/java/com/gepe/app/auth/internal/entity/AuthIdentity.java`

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

/**
 * Satu baris = satu metode login untuk satu user.
 *  - provider "credentials" → provider_id = email, password_hash terisi
 *  - provider "google"     → provider_id = sub google, password_hash NULL
 */
@Entity
@Table(name = "auth_identities", schema = "auth")
@Getter
@Setter
public class AuthIdentity {

    public static final String PROVIDER_CREDENTIALS = "credentials";
    public static final String PROVIDER_GOOGLE = "google";

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_id", nullable = false, length = 255)
    private String providerId;

    @Column(length = 320)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthIdentity() {}

    public AuthIdentity(UUID userId, String provider, String providerId, String email, String passwordHash) {
        this.userId = userId;
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = Uuidv7.generate();
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

### 12.3 `src/main/java/com/gepe/app/auth/internal/repository/UserRepository.java`

```java
package com.gepe.app.auth.internal.repository;

import com.gepe.app.auth.internal.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
```

### 12.4 `src/main/java/com/gepe/app/auth/internal/repository/AuthIdentityRepository.java`

```java
package com.gepe.app.auth.internal.repository;

import com.gepe.app.auth.internal.entity.AuthIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, UUID> {

    Optional<AuthIdentity> findByProviderAndProviderId(String provider, String providerId);

    List<AuthIdentity> findByUserId(UUID userId);

    boolean existsByUserIdAndProvider(UUID userId, String provider);
}
```

### 12.5 Aturan account linking (dipraktikkan di `AuthService`, lihat §19)

| Skenario | Aksi |
|---|---|
| Login **credentials** dulu, lalu login **google** (email sama) | User & creds identity sudah ada → buat **google identity** untuk user yang sama → tandai email verified |
| Login **google** dulu, lalu **set password** | User & google identity sudah ada → buat **credentials identity** (password_hash) untuk user yang sama |
| Login **google**, email sudah dipakai user lain tanpa google identity | Link ke user tsb (jangan buat user baru) |
| Login **google**, email belum ada | Buat `users` baru + google identity + publish `UserRegistered` |
| Login **credentials**, email belum ada | Buat `users` baru (email_verified_at NULL) + credentials identity + publish `UserRegistered` |

> **Catatan keamanan (best practice):** binding by **email** adalah pendekatan standar (Auth0/NextAuth account
> linking), tetapi mengasumsikan email = bukti kepemilikan akun. Untuk aplikasi sensitive, tambahkan langkah verifikasi
> email (kirim link) sebelum linking — di luar scope sekarang.

---

## 13. Signing Key — Boundary-safe DTOs

### 13.1 `src/main/java/com/gepe/app/auth/internal/dto/SigningKeyStatus.java`

```java
package com.gepe.app.auth.internal.dto;

public enum SigningKeyStatus {
    ACTIVE,
    PREVIOUS,
    RETIRED
}
```

### 13.2 `src/main/java/com/gepe/app/auth/internal/dto/SigningKeyData.java`

```java
package com.gepe.app.auth.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record SigningKeyData(
        UUID kid,
        String publicKey,
        byte[] privateKeyCipher,
        String encKeyId,
        SigningKeyStatus status,
        Instant notBefore,
        Instant notAfter) {
}
```

---

## 14. Signing Key — Service

### `src/main/java/com/gepe/app/auth/internal/service/SigningKeyService.java`

```java
package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.dto.SigningKeyData;
import com.gepe.app.auth.internal.dto.SigningKeyStatus;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.auth.internal.repository.SigningKeyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SigningKeyService {

    private final SigningKeyRepository signingKeyRepository;

    public Optional<SigningKeyData> getActive() {
        return signingKeyRepository
                .findFirstByStatusOrderByNotBeforeDesc(SigningKey.Status.ACTIVE)
                .map(this::toData);
    }

    public List<SigningKeyData> getActiveOrPrevious() {
        return signingKeyRepository
                .findByStatusIn(List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS))
                .stream()
                .map(this::toData)
                .toList();
    }

    public List<SigningKeyData> getActiveOrPreviousNotExpired(Instant now) {
        return signingKeyRepository
                .findByStatusInAndNotAfterAfter(
                        List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS), now)
                .stream()
                .map(this::toData)
                .toList();
    }

    private SigningKeyData toData(SigningKey e) {
        return new SigningKeyData(
                e.getKid(),
                e.getPublicKey(),
                e.getPrivateKeyCipher(),
                e.getEncKeyId(),
                mapStatus(e.getStatus()),
                e.getNotBefore(),
                e.getNotAfter());
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

---

## 15. Signing Key — Entity, Repository

### 15.1 `src/main/java/com/gepe/app/auth/internal/entity/SigningKey.java`

```java
package com.gepe.app.auth.internal.entity;

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
public class SigningKey {

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

    protected SigningKey() {}

    public SigningKey(UUID kid, String publicKey, byte[] privateKeyCipher, String encKeyId,
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

    public enum Status {
        ACTIVE,
        PREVIOUS,
        RETIRED
    }
}
```

### 15.2 `src/main/java/com/gepe/app/auth/internal/repository/SigningKeyRepository.java`

```java
package com.gepe.app.auth.internal.repository;

import com.gepe.app.auth.internal.entity.SigningKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    Optional<SigningKey> findFirstByStatusOrderByNotBeforeDesc(SigningKey.Status status);

    List<SigningKey> findByStatusIn(List<SigningKey.Status> statuses);

    List<SigningKey> findByStatusInAndNotAfterAfter(
            List<SigningKey.Status> statuses, Instant now);

    Optional<SigningKey> findByKidAndStatusIn(UUID kid, List<SigningKey.Status> statuses);
}
```

---

## 16. Signing Key — Rotation Jobs, Scheduler & Seeder

> **AGENTS.md §2.4:** Quartz `Job` (pekerjaan) **selalu dipisah** dari `*Scheduler` (registrasi `JobDetail`/`Trigger`).
> Keduanya hidup di `internal/job`.
>
> **Invariant:** **tepat 1 `ACTIVE`** di DB, di-enforce juga oleh partial unique index di migration `V1`.

### 16.1 `src/main/java/com/gepe/app/auth/internal/job/SigningKeyRotationJob.java`

```java
package com.gepe.app.auth.internal.job;

import com.gepe.app.auth.internal.crypto.MasterKeyProvider;
import com.gepe.app.auth.internal.crypto.RsaKeyService;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.auth.internal.repository.SigningKeyRepository;
import com.gepe.app.platform.support.Uuidv7;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
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

        // Transition current ACTIVE → PREVIOUS (keep for verifying still-valid ATs via kid)
        signingKeyRepository.findFirstByStatusOrderByNotBeforeDesc(SigningKey.Status.ACTIVE)
                .ifPresent(active -> {
                    active.setStatus(SigningKey.Status.PREVIOUS);
                    active.setNotAfter(now.plus(OVERLAP_WINDOW));
                    signingKeyRepository.save(active);
                    log.info("Transitioned signing key to PREVIOUS: kid={}, not_after={}",
                             active.getKid(), active.getNotAfter());
                });

        // Generate new ACTIVE key — kid HARUS v7 via Uuidv7.generate()
        KeyPair keyPair = rsaKeyService.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey priv = (RSAPrivateKey) keyPair.getPrivate();

        String pubBase64 = rsaKeyService.publicKeyToBase64(pub);
        byte[] privCipher = rsaKeyService.encryptPrivateKey(priv);

        SigningKey newKey = new SigningKey(
                Uuidv7.generate(),
                pubBase64,
                privCipher,
                masterKeyProvider.getCurrentKeyId(),
                SigningKey.Status.ACTIVE,
                now,
                null
        );

        signingKeyRepository.save(newKey);
        log.info("Generated new ACTIVE signing key: kid={}", newKey.getKid());
    }
}
```

> Di dalam satu transaksi job: ACTIVE lama di-set PREVIOUS **sebelum** ACTIVE baru di-insert — partial unique index
> `uq_signing_keys_single_active` (V1) memastikan tak pernah ada 2 baris ACTIVE sekaligus.

### 16.2 `src/main/java/com/gepe/app/auth/internal/job/SigningKeyRotationScheduler.java`

```java
package com.gepe.app.auth.internal.job;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
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

### 16.3 `src/main/java/com/gepe/app/auth/internal/job/MasterKeyRotationJob.java`

```java
package com.gepe.app.auth.internal.job;

import com.gepe.app.auth.internal.crypto.AesGcmService;
import com.gepe.app.auth.internal.crypto.MasterKeyProvider;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.auth.internal.repository.SigningKeyRepository;
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
                byte[] pkcs8 = aesGcmService.decrypt(key.getPrivateKeyCipher(), oldKeyId);
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

### 16.4 `src/main/java/com/gepe/app/auth/internal/job/MasterKeyRotationScheduler.java`

```java
package com.gepe.app.auth.internal.job;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
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

## 17. Session Management

> **1 refresh token aktif = 1 session.** `session_id` **stabil** antar rotasi (bernilai id token pertama/root) —
> jadi walau refresh di-rotate, session yang sama tetap teridentifikasi. Karena AT stateless (toleransi tanpa
> deny-list), "revoke session" = mencabut refresh token sehingga **tidak ada token baru** yang bisa ditebitkan untuk
> session itu; AT yang sudah beredar tetap valid sampai expiry.

### 17.1 `src/main/java/com/gepe/app/auth/internal/entity/RefreshToken.java`

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
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;                 // root id — stabil antar rotasi

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "device_info", length = 500)
    private String deviceInfo;              // user-agent

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "parent_token_id")
    private UUID parentTokenId;             // untuk deteksi reuse

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

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

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActive() {
        return !isExpired() && !isRevoked();
    }

    public void revoke() {
        revokedAt = Instant.now();
    }

    public void markUsed() {
        lastUsedAt = Instant.now();
    }
}
```

### 17.2 `src/main/java/com/gepe/app/auth/internal/repository/RefreshTokenRepository.java`

```java
package com.gepe.app.auth.internal.repository;

import com.gepe.app.auth.internal.entity.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findByIdAndUserId(UUID id, UUID userId);

    /** Keyset (cursor) query — session aktif per user, terbaru dulu (AGENTS.md §4). */
    @Query("""
           select rt from RefreshToken rt
           where rt.userId = :userId
             and rt.revokedAt is null
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
            org.springframework.data.domain.Pageable pageable);

    long countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(UUID userId, Instant now);

    @Modifying
    @Query("update RefreshToken rt set rt.revokedAt = :now "
           + "where rt.userId = :userId and rt.revokedAt is null and rt.id <> :excludeId")
    int revokeAllExcept(@Param("userId") UUID userId, @Param("excludeId") UUID excludeId,
                        @Param("now") Instant now);

    List<RefreshToken> findBySessionId(UUID sessionId);
}
```

### 17.3 `src/main/java/com/gepe/app/auth/internal/dto/SessionInfo.java` & `SessionPage.java`

```java
package com.gepe.app.auth.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionInfo(
        UUID sessionId,
        UUID refreshTokenId,
        String deviceInfo,
        String ipAddress,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        boolean isCurrent) {
}
```

```java
package com.gepe.app.auth.internal.dto;

import java.util.List;

public record SessionPage(List<SessionInfo> items, String nextCursor) {
}
```

### 17.4 `src/main/java/com/gepe/app/auth/internal/service/SessionService.java`

> List session = **cursor/keyset** (AGENTS.md §4): `ORDER BY issued_at DESC, id DESC`, nextCursor = base64
> `(issued_at_epoch_ms:id)`. `isCurrent` dihitung dari refresh token **saat ini** yang dikirim klien (header
> `X-Refresh-Token`), karena session saat ini TIDAK boleh di-revoke dari daftar.

```java
package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.dto.SessionInfo;
import com.gepe.app.auth.internal.dto.SessionPage;
import com.gepe.app.auth.internal.entity.RefreshToken;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.repository.RefreshTokenRepository;
import com.gepe.app.platform.exception.ServiceException;
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
        Instant afterIssuedAt;
        UUID afterId;

        if (cursor == null || cursor.isBlank()) {
            afterIssuedAt = Instant.MAX;
            afterId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        } else {
            var decoded = decodeCursor(cursor);
            afterIssuedAt = decoded[0];
            afterId = decoded[1];
        }

        List<RefreshToken> rows = refreshTokenRepository.findActivePage(
                userId, Instant.now(), afterIssuedAt, afterId, PageRequest.of(0, pageSize + 1));

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
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Instant[] decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor),
                                    java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 2);
            return new Instant[] {
                Instant.ofEpochMilli(Long.parseLong(parts[0])),
                UUID.fromString(parts[1])
            };
        } catch (Exception e) {
            throw new ServiceException(AuthError.CURRENT_TOKEN_REQUIRED);
        }
    }
}
```

> Cursor **opaque** — klien tidak pernah melihat nilai mentah `issued_at`/`id` (AGENTS.md §4.3).

---

## 18. Refresh Token — Service

### `src/main/java/com/gepe/app/auth/internal/service/RefreshTokenService.java`

```java
package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.dto.RotatedToken;
import com.gepe.app.auth.internal.dto.TokenWithId;
import com.gepe.app.auth.internal.entity.RefreshToken;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.repository.RefreshTokenRepository;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.support.Uuidv7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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

    private final RefreshTokenRepository refreshTokenRepository;

    public TokenWithId issue(UUID userId, String deviceInfo, String ipAddress, Duration ttl) {
        String raw = generateRawToken();
        RefreshToken token = new RefreshToken();
        token.setId(Uuidv7.generate());
        token.setSessionId(token.getId());       // token pertama = root session
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
        rotated.setSessionId(current.getSessionId());   // SAMA — session tetap
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
    public java.util.Optional<UUID> findIdByRawToken(String rawToken) {
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
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
```

> Deteksi reuse: token yang sudah `revoked_at` terisi = sudah pernah di-rotate → dipakai ulang → seluruh sesi dicabut.

---

## 19. DTOs (delivery/http ↔ Service)

> Records ini dipakai service ↔ controller di dalam modul (AGENTS.md §2.6). `public` karena controller berada di
> `internal/delivery/http` (sub-package berbeda dengan `internal/dto`).

### `src/main/java/com/gepe/app/auth/internal/dto/LoginRequest.java`

```java
package com.gepe.app.auth.internal.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}
```

### `src/main/java/com/gepe/app/auth/internal/dto/RefreshRequest.java`

```java
package com.gepe.app.auth.internal.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {
}
```

### `src/main/java/com/gepe/app/auth/internal/dto/LogoutRequest.java`

```java
package com.gepe.app.auth.internal.dto;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(@NotBlank String refreshToken) {
}
```

### `src/main/java/com/gepe/app/auth/internal/dto/SetPasswordRequest.java`

```java
package com.gepe.app.auth.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetPasswordRequest(
        @NotBlank @Size(min = 8, max = 72) String newPassword) {
}
```

### `src/main/java/com/gepe/app/auth/internal/dto/TokenResponse.java`

```java
package com.gepe.app.auth.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(
        String accessToken,
        String refreshToken,
        UUID refreshTokenId,
        UUID sessionId,
        UUID userId) {
}
```

### `src/main/java/com/gepe/app/auth/internal/dto/TokenWithId.java` & `RotatedToken.java`

```java
package com.gepe.app.auth.internal.dto;

import java.util.UUID;

public record TokenWithId(UUID id, String raw) {}
```

```java
package com.gepe.app.auth.internal.dto;

import java.util.UUID;

public record RotatedToken(UUID id, String raw, UUID userId, UUID sessionId) {}
```

---

## 20. OAuth Google (backend)

> **Web only.** Semua "dance" OAuth (authorize → callback → exchange) berjalan di backend. Mobile ditunda.
> Flow: browser → `/oauth2/authorization/google` (Spring redirect) → Google → `/login/oauth2/code/google` →
> `GoogleAuthSuccessHandler` → `AuthService.googleLogin(sub, email)` → cookie + redirect.

### 20.1 `src/main/java/com/gepe/app/auth/internal/oauth/GoogleOAuth2UserService.java`

```java
package com.gepe.app.auth.internal.oauth;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class GoogleOAuth2UserService extends DefaultOAuth2UserService {

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(userRequest);
        Map<String, Object> attrs = user.getAttributes();
        UUID sub = parseSub(attrs);
        String email = (String) attrs.get("email");
        return new GoogleUser(sub, email, user);
    }

    private UUID parseSub(Map<String, Object> attrs) {
        Object sub = attrs.get("sub");
        if (sub == null) throw new OAuth2AuthenticationException("Missing sub claim from Google");
        try {
            return UUID.fromString(sub.toString());
        } catch (IllegalArgumentException e) {
            throw new OAuth2AuthenticationException("Google sub is not a UUID: " + sub);
        }
    }

    record GoogleUser(UUID sub, String email, OAuth2User delegate) {}
}
```

### 20.2 `src/main/java/com/gepe/app/auth/internal/oauth/GoogleAuthSuccessHandler.java`

```java
package com.gepe.app.auth.internal.oauth;

import com.gepe.app.auth.internal.cookie.CookieAuthHelper;
import com.gepe.app.auth.internal.oauth.GoogleOAuth2UserService.GoogleUser;
import com.gepe.app.auth.internal.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GoogleAuthSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final CookieAuthHelper cookieAuthHelper;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        GoogleUser googleUser = (GoogleUser) token.getPrincipal();

        var result = authService.googleLogin(
                googleUser.sub(), googleUser.email(), request.getHeader("User-Agent"),
                request.getRemoteAddr());

        cookieAuthHelper.setAccessTokenCookie(response, result.accessToken());
        response.sendRedirect("/web/auth/callback?session=" + result.sessionId());
    }
}
```

> `CookieAuthHelper` = helper untuk set/clear cookie (dipakai `AuthWebController` juga), membaca config dari
> `JwtProperties.cookie()`.

---

## 21. Auth Service

### `src/main/java/com/gepe/app/auth/internal/service/AuthService.java`

> Use-case orchestration (AGENTS.md §2): satu-satunya tempat publish event, entity → DTO di boundary.
> `public` karena dipakai controller di `internal/delivery/http`.

```java
package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.UserAuthenticated;
import com.gepe.app.auth.api.UserRegistered;
import com.gepe.app.auth.internal.crypto.PasswordHasher;
import com.gepe.app.auth.internal.dto.RotatedToken;
import com.gepe.app.auth.internal.dto.TokenResponse;
import com.gepe.app.auth.internal.dto.TokenWithId;
import com.gepe.app.auth.internal.entity.AuthIdentity;
import com.gepe.app.auth.internal.entity.RefreshToken;
import com.gepe.app.auth.internal.entity.User;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.jwt.JwtService;
import com.gepe.app.auth.internal.repository.AuthIdentityRepository;
import com.gepe.app.auth.internal.repository.UserRepository;
import com.gepe.app.platform.exception.ServiceException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final AuthIdentityRepository authIdentityRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordHasher passwordHasher;
    private final ApplicationEventPublisher events;

    // ── login credentials ──
    public TokenResponse login(String email, String password, String deviceInfo, String ipAddress) {
        AuthIdentity cred = authIdentityRepository
                .findByProviderAndProviderId(AuthIdentity.PROVIDER_CREDENTIALS, email)
                .orElseThrow(() -> new ServiceException(AuthError.INVALID_CREDENTIALS));

        if (!passwordHasher.matches(password, cred.getPasswordHash())) {
            throw new ServiceException(AuthError.INVALID_CREDENTIALS);
        }

        User user = userRepository.findById(cred.getUserId())
                .orElseThrow(() -> new ServiceException(AuthError.INVALID_CREDENTIALS));

        return issueTokens(user, deviceInfo, ipAddress);
    }

    // ── login google (backend OAuth) + account linking ──
    public TokenResponse googleLogin(UUID googleSub, String email, String deviceInfo, String ipAddress) {
        AuthIdentity existing = authIdentityRepository
                .findByProviderAndProviderId(AuthIdentity.PROVIDER_GOOGLE, googleSub.toString())
                .orElse(null);

        User user;
        if (existing != null) {
            user = userRepository.findById(existing.getUserId())
                    .orElseThrow(() -> new ServiceException(AuthError.IDENTITY_NOT_FOUND));
        } else {
            // belum ada google identity → coba link by email
            user = userRepository.findByEmail(email).orElse(null);
            boolean isNew = user == null;
            if (isNew) {
                user = new User(email, Instant.now());            // google sudah verify email
                userRepository.save(user);
            } else {
                user.markEmailVerified();                          // google membuktikan email
                userRepository.save(user);
            }
            authIdentityRepository.save(new AuthIdentity(
                    user.getId(), AuthIdentity.PROVIDER_GOOGLE, googleSub.toString(), email, null));
            if (isNew) {
                events.publishEvent(new UserRegistered(user.getId(), user.getEmail()));
            }
        }

        return issueTokens(user, deviceInfo, ipAddress);
    }

    // ── register via credentials (email + password) ──
    public TokenResponse register(String email, String password, String deviceInfo, String ipAddress) {
        if (userRepository.existsByEmail(email)) {
            throw new ServiceException(AuthError.EMAIL_ALREADY_LINKED);
        }
        User user = new User(email, null);                         // credentials → verified NULL
        userRepository.save(user);

        authIdentityRepository.save(new AuthIdentity(
                user.getId(), AuthIdentity.PROVIDER_CREDENTIALS, email, email,
                passwordHasher.hash(password)));

        events.publishEvent(new UserRegistered(user.getId(), user.getEmail()));
        return issueTokens(user, deviceInfo, ipAddress);
    }

    // ── set password (untuk user yang login via google saja) → binding ke credentials ──
    public void setPassword(UUID userId, String newPassword) {
        if (authIdentityRepository.existsByUserIdAndProvider(userId, AuthIdentity.PROVIDER_CREDENTIALS)) {
            throw new ServiceException(AuthError.IDENTITY_EXISTS);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ServiceException(AuthError.IDENTITY_NOT_FOUND));
        authIdentityRepository.save(new AuthIdentity(
                userId, AuthIdentity.PROVIDER_CREDENTIALS, user.getEmail(), user.getEmail(),
                passwordHasher.hash(newPassword)));
    }

    // ── refresh ──
    public TokenResponse refresh(String rawRefreshToken, String deviceInfo, String ipAddress) {
        RotatedToken rotated = refreshTokenService.rotate(rawRefreshToken, deviceInfo, ipAddress);

        User user = userRepository.findById(rotated.userId())
                .orElseThrow(() -> new ServiceException(AuthError.IDENTITY_NOT_FOUND));

        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), List.of("ROLE_USER")).serialize();

        return new TokenResponse(accessToken, rotated.raw(), rotated.id(), rotated.sessionId(), user.getId());
    }

    // ── logout → hanya revoke session (AT stateless tetap valid sampai expiry) ──
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeByIdByHash(rawRefreshToken);
    }

    private TokenResponse issueTokens(User user, String deviceInfo, String ipAddress) {
        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), List.of("ROLE_USER")).serialize();

        TokenWithId rt = refreshTokenService.issue(user.getId(), deviceInfo, ipAddress, Duration.ofDays(30));

        events.publishEvent(new UserAuthenticated(user.getId(), user.getEmail()));
        return new TokenResponse(accessToken, rt.raw(), rt.id(), rt.id(), user.getId());
    }
}
```

> `RefreshTokenService.revokeByIdByHash(hash)` = cari by `token_hash` lalu revoke — dipakai `logout`.

---

## 22. Controllers (`internal/delivery/http/` — BUKAN di `api/`)

> **AGENTS.md §2:** controller REST berada di **`internal/delivery/http`**, `package-private`, dan hanya bergantung pada
> `internal/service/` + `internal/dto/`. `api/` adalah untuk modul *lain* memanggil, bukan untuk HTTP entry point.

### 22.1 `src/main/java/com/gepe/app/auth/internal/delivery/http/AuthController.java` (API — Bearer)

```java
package com.gepe.app.auth.internal.delivery.http;

import com.gepe.app.auth.internal.dto.LoginRequest;
import com.gepe.app.auth.internal.dto.LogoutRequest;
import com.gepe.app.auth.internal.dto.RefreshRequest;
import com.gepe.app.auth.internal.dto.SetPasswordRequest;
import com.gepe.app.auth.internal.dto.SessionPage;
import com.gepe.app.auth.internal.dto.TokenResponse;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.service.AuthService;
import com.gepe.app.auth.internal.service.RefreshTokenService;
import com.gepe.app.auth.internal.service.SessionService;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.web.context.RequestContext;
import com.gepe.app.platform.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final RefreshTokenService refreshTokenService;
    private final MessageHelper messageHelper;

    @PostMapping("/register")
    ResponseEntity<ApiResponse<TokenResponse>> register(@Valid @RequestBody LoginRequest request,
            HttpServletRequest http) {
        TokenResponse tokens = authService.register(request.email(), request.password(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.login_success"), tokens));
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest http) {
        TokenResponse tokens = authService.login(request.email(), request.password(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.login_success"), tokens));
    }

    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request,
            HttpServletRequest http) {
        TokenResponse tokens = authService.refresh(request.refreshToken(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.refresh_success"), tokens));
    }

    @PostMapping("/logout")
    ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.logout_success"), null));
    }

    @PostMapping("/password")
    ResponseEntity<ApiResponse<Void>> setPassword(@Valid @RequestBody SetPasswordRequest request) {
        UUID userId = RequestContext.getCurrentUserId();
        authService.setPassword(userId, request.newPassword());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.password_set_success"), null));
    }

    @GetMapping("/sessions")
    ResponseEntity<ApiResponse<SessionPage>> sessions(
            @RequestHeader(value = "X-Refresh-Token", required = false) String currentRefreshToken,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        UUID userId = RequestContext.getCurrentUserId();
        UUID currentTokenId = currentRefreshToken != null
                ? resolveTokenId(currentRefreshToken)
                : null;
        return ResponseEntity.ok(new ApiResponse<>(null,
                sessionService.listActive(userId, currentTokenId, cursor, limit)));
    }

    @DeleteMapping("/sessions/{refreshTokenId}")
    ResponseEntity<ApiResponse<Void>> revokeSession(
            @PathVariable UUID refreshTokenId,
            @RequestHeader(value = "X-Refresh-Token", required = false) String currentRefreshToken) {
        UUID userId = RequestContext.getCurrentUserId();
        UUID currentTokenId = currentRefreshToken != null ? resolveTokenId(currentRefreshToken) : null;
        sessionService.revokeSession(userId, refreshTokenId, currentTokenId);
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.sessions_revoked_success"), null));
    }

    @DeleteMapping("/sessions")
    ResponseEntity<ApiResponse<Void>> revokeOthers(
            @RequestHeader("X-Refresh-Token") String currentRefreshToken) {
        UUID userId = RequestContext.getCurrentUserId();
        UUID currentTokenId = resolveTokenId(currentRefreshToken);
        sessionService.revokeAllExcept(userId, currentTokenId);
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.sessions_revoked_success"), null));
    }

    private UUID resolveTokenId(String refreshToken) {
        return refreshTokenService.findIdByRawToken(refreshToken)
                .orElseThrow(() -> new ServiceException(AuthError.CURRENT_TOKEN_REQUIRED));
    }
}
```

> `resolveTokenId` membaca refresh token **saat ini** dari header `X-Refresh-Token` untuk menghitung `isCurrent`
> (list) dan untuk menolak revoke session sendiri / mengecualikannya saat revoke-others.

### 22.2 `src/main/java/com/gepe/app/auth/internal/delivery/http/AuthWebController.java` (WebApp — Cookie)

```java
package com.gepe.app.auth.internal.delivery.http;

import com.gepe.app.auth.internal.dto.LoginRequest;
import com.gepe.app.auth.internal.dto.LogoutRequest;
import com.gepe.app.auth.internal.dto.RefreshRequest;
import com.gepe.app.auth.internal.dto.TokenResponse;
import com.gepe.app.auth.internal.cookie.CookieAuthHelper;
import com.gepe.app.auth.internal.service.AuthService;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
    private final CookieAuthHelper cookieAuthHelper;
    private final MessageHelper messageHelper;

    @PostMapping("/login")
    ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest http, HttpServletResponse response) {
        TokenResponse tokens = authService.login(request.email(), request.password(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        cookieAuthHelper.setAccessTokenCookie(response, tokens.accessToken());

        TokenResponse body = new TokenResponse(
                null, tokens.refreshToken(), tokens.refreshTokenId(), tokens.sessionId(), tokens.userId());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.login_success"), body));
    }

    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request,
            HttpServletRequest http, HttpServletResponse response) {
        TokenResponse tokens = authService.refresh(request.refreshToken(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        cookieAuthHelper.setAccessTokenCookie(response, tokens.accessToken());

        TokenResponse body = new TokenResponse(
                null, tokens.refreshToken(), tokens.refreshTokenId(), tokens.sessionId(), tokens.userId());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.refresh_success"), body));
    }

    @PostMapping("/logout")
    ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request,
            HttpServletResponse response) {
        authService.logout(request.refreshToken());
        cookieAuthHelper.clearAccessTokenCookie(response);
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.logout_success"), null));
    }
}
```

### `src/main/java/com/gepe/app/auth/internal/cookie/CookieAuthHelper.java`

```java
package com.gepe.app.auth.internal.cookie;

import com.gepe.app.auth.internal.jwt.JwtProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CookieAuthHelper {

    private final JwtProperties properties;

    public void setAccessTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(properties.cookie().name(), token)
                .httpOnly(true)
                .secure(properties.cookie().secure())
                .path(properties.cookie().path())
                .sameSite(properties.cookie().sameSite())
                .maxAge(properties.cookie().maxAge().getSeconds())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearAccessTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(properties.cookie().name(), "")
                .httpOnly(true)
                .secure(properties.cookie().secure())
                .path(properties.cookie().path())
                .sameSite(properties.cookie().sameSite())
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
```

---

## 23. Database Migrations

> Semua tabel yang akan di-list via endpoint wajib punya **composite index untuk cursor pagination** (AGENTS.md §3.7 &
> §4): pola `(filter_col, sort_col DESC, id DESC)`. FK **lintas schema dilarang** (AGENTS.md §3.3) — FK di sini semua
> **dalam schema `auth`** (diizinkan).

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

-- hanya BOLEH ada 1 baris ACTIVE (invariant "1 RSA aktif")
CREATE UNIQUE INDEX uq_signing_keys_single_active
    ON auth.signing_keys (status)
    WHERE status = 'ACTIVE';

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
    session_id      UUID            NOT NULL,
    user_id         UUID            NOT NULL,
    token_hash      VARCHAR(128)    NOT NULL,
    device_info     VARCHAR(500),
    ip_address      VARCHAR(45),
    parent_token_id UUID,
    last_used_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ     NOT NULL,
    issued_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    rotated_at      TIMESTAMPTZ,

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES auth.users (id) ON DELETE CASCADE
);

-- cursor pagination: list session aktif per user, terbaru dulu (AGENTS.md §4)
CREATE INDEX idx_refresh_tokens_user_issued
    ON auth.refresh_tokens (user_id, issued_at DESC, id DESC);

-- grouping/revoke per rantai session
CREATE INDEX idx_refresh_tokens_session
    ON auth.refresh_tokens (session_id);
```

### `src/main/resources/db/migration/auth/V3__users.sql`

```sql
CREATE TABLE auth.users (
    id                 UUID            NOT NULL,
    email              VARCHAR(320)    NOT NULL,
    email_verified_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- global list user (bila nanti perlu di-list)
CREATE INDEX idx_users_created
    ON auth.users (created_at DESC, id DESC);

CREATE TABLE auth.auth_identities (
    id              UUID            NOT NULL,
    user_id         UUID            NOT NULL,
    provider        VARCHAR(20)     NOT NULL,   -- 'credentials' | 'google' | dst
    provider_id     VARCHAR(255)    NOT NULL,   -- google: sub; credentials: email
    email           VARCHAR(320),
    password_hash   VARCHAR(255),               -- hanya utk provider 'credentials'
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_auth_identities PRIMARY KEY (id),
    CONSTRAINT uq_auth_identities_provider UNIQUE (provider, provider_id),
    CONSTRAINT fk_auth_identities_user
        FOREIGN KEY (user_id) REFERENCES auth.users (id) ON DELETE CASCADE
);

CREATE INDEX idx_auth_identities_user
    ON auth.auth_identities (user_id);
```

> Urutan migrasi: `V1` (signing_keys) → `V2` (refresh_tokens, butuh `auth.users`) → `V3` (users + auth_identities).
> Karena `V2` mereferensikan `auth.users`, pastikan `V3` dijalankan bersama (satu batch Flyway) atau tukar urutan
> `V2`/`V3`. **(Flyway tidak mengubah urutan setelah dirilis — jangan edit versi yang sudah terpakai di lingkungan
> mana pun; gunakan migrasi baru bila perlu.)**

---

## 24. Initial Key Seeding

### `src/main/java/com/gepe/app/auth/internal/job/SigningKeySeeder.java`

```java
package com.gepe.app.auth.internal.job;

import com.gepe.app.auth.internal.crypto.MasterKeyProvider;
import com.gepe.app.auth.internal.crypto.RsaKeyService;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.auth.internal.repository.SigningKeyRepository;
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

            SigningKey key = new SigningKey(
                    Uuidv7.generate(),
                    rsaKeyService.publicKeyToBase64(pub),
                    rsaKeyService.encryptPrivateKey(priv),
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

## 25. Verify: Modularity Test

Setelah semua code di atas ditambahkan, jalankan:

```bash
./mvnw test -Dtest="ModularityTests"
```

Test harus **hijau** karena:
- Hanya `auth/api/*` yang `public` sebagai kontrak antar modul.
- Semua entity/repository/service/jwt/crypto/job/dto/exception/config/oauth/delivery hidup di `auth/internal/**`.
- Controller (`AuthController`, `AuthWebController`) di `internal/delivery/http` hanya bergantung pada
  `internal/service/` + `internal/dto/`.
- Tidak ada import dari modul lain selain ke `platform` (shared module).

> Jika merah → **jangan** bypass test. Perbaiki arsitekturnya (AGENTS.md §7).

---

## 26. Environment Variables

```bash
# Master key (AES-256, 32 bytes)
export MASTER_KEY_CURRENT=$(openssl rand -base64 32)

# Google OAuth (web)
export GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
export GOOGLE_CLIENT_SECRET=xxxx
```

> **Tidak ada** Redis deny-list env — deny-list sudah dibuang.

---

## 27. Endpoint Summary

| Method | Route | Chain | Auth | Body/Header | Response |
|---|---|---|---|---|---|
| POST | `/api/auth/register` | api | — | `{ email, password }` | `{ accessToken, refreshToken, refreshTokenId, sessionId, userId }` |
| POST | `/api/auth/login` | api | — | `{ email, password }` | token pair |
| POST | `/api/auth/refresh` | api | — | `{ refreshToken }` | token pair baru |
| POST | `/api/auth/logout` | api | Bearer | `{ refreshToken }` | 200 (session di-revoke; AT tetap valid sampai expiry) |
| POST | `/api/auth/password` | api | Bearer | `{ newPassword }` | 200 (bind ke credentials, user google-only) |
| GET | `/api/auth/sessions` | api | Bearer | `X-Refresh-Token` + `cursor`/`limit` | `{ items: SessionInfo[], nextCursor }` |
| DELETE | `/api/auth/sessions/{refreshTokenId}` | api | Bearer | `X-Refresh-Token` | 200; **401/400** bila id = session saat ini |
| DELETE | `/api/auth/sessions` | api | Bearer | `X-Refresh-Token` | 200 (revoke semua kecuali current) |
| GET | `/oauth2/authorization/google` | web | — | — | redirect ke Google (backend) |
| GET | `/login/oauth2/code/google` | web | — | — | callback backend → cookie + redirect |
| POST | `/web/auth/login` | web | — | `{ email, password }` | token pair + `Set-Cookie: ACCESS_TOKEN` |
| POST | `/web/auth/refresh` | web | — | `{ refreshToken }` | token pair + `Set-Cookie` |
| POST | `/web/auth/logout` | web | — | `{ refreshToken }` | 200 + clear cookie |

---

## 28. Checklist Eksekusi (Urutan yang Disarankan)

1. [ ] Tambah/cek dependencies di `pom.xml` (oauth2-resource-server, oauth2-client). **Tanpa** redis deny-list.
2. [ ] Tambah `flyway.locations` dan `app.security.*` (**tanpa** `deny-list-prefix`) + `spring.security.oauth2.client.*` di `application.yaml`
3. [ ] Tambah `classpath:i18n/auth/messages` di `I18nConfig.java`
4. [ ] Buat file i18n: `messages.properties` dan `messages_id.properties`
5. [ ] Buat migration SQL: `V1__signing_keys.sql` (+ partial unique index), `V2__refresh_tokens.sql` (+ session), `V3__users.sql`
6. [ ] Buat package `com.gepe.app.auth` dengan `package-info.java`
7. [ ] Buat `api/` → `AuthApi.java`, `CurrentUser.java`, `UserAuthenticated.java`, `UserRegistered.java`
8. [ ] Buat `internal/exception/AuthError.java`
9. [ ] Buat `internal/entity/*` (User, AuthIdentity, RefreshToken, SigningKey)
10. [ ] Buat `internal/repository/*` (UserRepository, AuthIdentityRepository, RefreshTokenRepository, SigningKeyRepository)
11. [ ] Buat `internal/jwt/*` (JwtProperties, JwtConfig, JwtClaims, JwtService, JwtAuthenticationToken, DbJwtDecoder)
12. [ ] Buat `internal/crypto/*` (MasterKeyProvider, AesGcmService, RsaKeyService, PasswordHasher)
13. [ ] Buat `internal/dto/*` (LoginRequest, RefreshRequest, LogoutRequest, SetPasswordRequest, TokenResponse, SessionInfo, SessionPage, TokenWithId, RotatedToken, SigningKeyData, SigningKeyStatus)
14. [ ] Buat `internal/service/*` (AuthService + account linking, SessionService, RefreshTokenService, SigningKeyService, AuthApiImpl)
15. [ ] Buat `internal/oauth/*` (GoogleOAuth2UserService, GoogleAuthSuccessHandler)
16. [ ] Buat `internal/cookie/*` (CookieAuthenticationFilter, CookieAuthHelper)
17. [ ] Buat `internal/job/*` (SigningKeyRotationJob/Scheduler, MasterKeyRotationJob/Scheduler, SigningKeySeeder)
18. [ ] Buat `internal/config/*` (AuthSecurityConfig + oauth2Login, OAuthConfig)
19. [ ] Buat controller di `internal/delivery/http/`: `AuthController.java`, `AuthWebController.java`
20. [ ] Set env `MASTER_KEY_CURRENT`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
21. [ ] Start Postgres
22. [ ] `./mvnw compile`
23. [ ] `./mvnw test -Dtest="ModularityTests"` — **harus hijau**
24. [ ] `./mvnw test`
25. [ ] `./mvnw spring-boot:run`

---

## Arsitektur Lengkap (Visual)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  REQUEST                                                                  │
│                                                                           │
│  Mobile        WebApp                    Google                           │
│    │              │                         │                             │
│    ▼              ▼                         ▼                             │
│  /api/**       /web/**          /oauth2/authorization/google              │
│  Bearer AT     Cookie AT          (backend redirect dance)                │
│    │              │                         │                             │
│    ▼              ▼                         ▼                             │
│  AuthSecurityConfig (3 chain)      GoogleAuthSuccessHandler               │
│  Chain1 /api  Bearer → JWT Decode   └→ AuthService.googleLogin()          │
│  Chain2 /web  Cookie → JWT Decode        (create/link user, cookie)       │
│  Chain3 /**   denyAll                                                      │
│                        │                                                   │
│                        ▼                                                   │
│  DbJwtDecoder (signing_keys: ACTIVE + PREVIOUS; kid di payload)            │
│                        │                                                   │
│  Session (refresh_tokens: session_id stabil, revocable)                    │
│  AT stateless → valid sampai expiry (tanpa deny-list Redis)                │
│                        │                                                   │
│  Principal = UUID userId → RequestContext.getCurrentUserId() ✅            │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Catatan Keamanan

1. **Master key di env** bukan KMS — memadai untuk skala ini. Jangan commit ke git.
2. **Rotasi master key** memerlukan `MASTER_KEY_PREVIOUS` tersisa sampai semua key selesai di-re-encrypt oleh `MasterKeyRotationJob`.
3. **Hanya 1 RSA `ACTIVE`** (di-enforce partial unique index). Key lama `PREVIOUS` dipertahankan untuk verifikasi AT lama via `kid`; di-`RETIRED` saat cohort-nya habis. Idealnya tambah cleanup job untuk menghapus `RETIRED` yang sudah sangat tua.
4. **AT stateless (toleransi):** setelah logout / revoke session, AT yang sudah beredar tetap valid sampai expiry. Tidak ada deny-list Redis → tidak ada network hop. Trade-off: periode "replay" terbatas seumur AT — perpendek `access-token-ttl` bila perlu.
5. **Revoke session** hanya mematikan refresh token (tidak ada AT baru); **tidak** mencabut AT yang sudah ada. Session saat ini tidak bisa di-revoke dari daftar (API menolak + UI disable).
6. **Refresh token reuse detection:** token yang sudah di-revoke (pernah di-rotate) dipakai lagi → semua sesi dicabut.
7. **Account linking by email** adalah standar (Auth0/NextAuth), tapi mengasumsikan email = bukti kepemilikan. Untuk aplikasi sensitive, tambahkan verifikasi email (link) sebelum linking.
8. **OAuth backend:** client-id/secret hanya di server; token Google tidak pernah dilihat frontend web. Mobile ditunda.
9. **Semua UUID v7** via `Uuidv7.generate()` — `users.id`, `auth_identities.id`, `refresh_tokens.id`/`session_id`, `signing_keys.kid`, `jti`. **Dilarang** `UUID.randomUUID()` (v4).
