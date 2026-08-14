# Auth Module — Panduan Lengkap

> Modul autentikasi untuk starter-app Spring Modulith.  
> Cakupan: login email/password, Google OAuth (PKCE dance di backend), JWT Bearer,
> session management (refresh token rotation), signing key rotation, account linking.

---

## 1. Arsitektur Ringkas

```
Client (Web / Mobile / SPA)
    │
    ├── POST /api/v1/auth/register   ──┐
    ├── POST /api/v1/auth/login       ─┤  credential
    ├── POST /api/v1/auth/refresh     ─┤
    ├── POST /api/v1/auth/logout      ─┘
    │
    ├── GET  /api/v1/auth/oauth/google       ──┐
    ├── GET  /auth/oauth/google/callback      ─┤  Google OAuth
    ├── POST /api/v1/auth/oauth/exchange      ─┘  (PKCE backend)
    │
    ├── GET  /api/v1/auth/me          ──  current user
    ├── GET  /api/v1/auth/sessions    ──  session list (cursor pagination)
    └── DELETE /api/v1/auth/sessions/** ──  session revoke

            │
            ▼
    AuthService ──── user/auth_identities ─► PostgreSQL (schema `auth`)
    │
    JwtService ──── signing_keys (RSA 2048) ─► DB-backed decoder
    │
    GoogleOAuthService ──── Redis (state, one-time-code)
```

**Prinsip utama:**
- **API-only** — semua endpoint REST; tidak ada cookie / server-side session.
- **Stateless** — access token (AT) short-lived, refresh token (RT) opaque + rotation.
- **Modularity** — module `auth` tidak bisa diakses langsung dari module lain; hanya lewat
  `api/` (interface sync) atau event (`ApplicationEventPublisher` + `@ApplicationModuleListener`).

---

## 2. Struktur Paket

```
com.gepe.app.auth/
├── api/                                  ← Public contract (inter‑module)
│   ├── UserService.java                   ←  sync interface
│   ├── UserDto.java                      ←  DTO data user (boundary, incl. roles + status)
│   ├── UserStatus.java                   ←  enum status akun (ACTIVE|SUSPENDED|DISABLED)
│   └── UserAuthenticated.java            ←  event (login)
└── internal/                             ← Implementation detail
    ├── config/
    │   └── AuthSecurityConfig.java       ← 6 SecurityFilterChain
    ├── crypto/
    │   ├── AesGcmService.java            ← AES-256-GCM encrypt/decrypt
    │   ├── MasterKeyProvider.java        ← loads MASTER_KEY_CURRENT
    │   ├── PasswordHasher.java           ← BCrypt wrapper
    │   └── RsaKeyService.java            ← RSA 2048 key generation + encrypt/decrypt
    ├── delivery/http/
    │   ├── AdminController.java          ← POST /admin/keys/rotate + PATCH /admin/users/{id}/status
    │   ├── AuthController.java           ← credential login/register/sessions
    │   ├── GoogleController.java         ← OAuth begin / callback / exchange
    │   └── JwksController.java           ← GET /.well-known/jwks.json
    ├── dto/                              ← DTO internal (service ↔ controller)
    │   ├── ExchangeRequest.java
    │   ├── GoogleAuthStartResponse.java
    │   ├── LoginRequest.java
│   ├── UserDetailsDto.java             ← GET /auth/me: UserDto (auth) + profil (module user)
    │   ├── LogoutRequest.java
    │   ├── RefreshRequest.java
    │   ├── RotatedKeyResponse.java
    │   ├── RotatedToken.java
    │   ├── SessionInfo.java
    │   ├── SessionPage.java
    │   ├── SetPasswordRequest.java
    │   ├── SigningKeyData.java
    │   ├── SigningKeyStatus.java
    │   ├── TokenResponse.java
    │   ├── TokenWithId.java
    │   └── UpdateUserStatusRequest.java
    ├── entity/
    │   ├── AuthIdentity.java             ← satu baris = satu metode login
    │   ├── RefreshToken.java
    │   ├── Role.java                     ← enum ADMIN, USER
    │   ├── SigningKey.java
    │   └── User.java                     ← incl. status (UserStatus) + status_changed_at
    ├── exception/
    │   └── AuthError.java               ← enum implementing ErrorCode
    ├── job/
    │   ├── MasterKeyRotationJob.java
    │   ├── MasterKeyRotationScheduler.java
    │   ├── SigningKeyRotationJob.java
    │   ├── SigningKeyRotationScheduler.java
    │   └── SigningKeySeeder.java         ← generates initial signing key
    ├── jwt/
    │   ├── DbJwtDecoder.java             ← custom JwtDecoder (DB-backed JWKS)
    │   ├── JwtAuthConverter.java         ← Jwt → AuthenticationToken
    │   ├── JwtAuthenticationToken.java
    │   ├── JwtClaims.java
    │   ├── JwtConfig.java
    │   ├── JwtProperties.java            ← @ConfigurationProperties
    │   └── JwtService.java               ← sign + issue RS256 JWT
    ├── listener/
    │   └── ProfileUpdateCacheEvictor.java ← evict cache /me saat ProfileUpdated (dari module user)
    ├── oauth/
    │   ├── GoogleOAuthService.java       ← PKCE + token exchange + id_token validation
    │   ├── OAuthConfig.java             ← @ConfigurationProperties + Google JwtDecoder
    │   └── OAuthProperties.java
    ├── repository/
    │   ├── AuthIdentityRepository.java
    │   ├── RefreshTokenRepository.java
    │   ├── SigningKeyRepository.java
    │   └── UserRepository.java
    └── service/
        ├── RoleResolver.java             ← default role USER + map roles → List<String>
        ├── UserServiceImpl.java          ← implements api/UserService
        ├── AuthService.java              ← use‑case orchestration + event publishing + status gate
        ├── UserDetailsCache.java         ← cache Redis komposit GET /auth/me (read-through + evict)
        ├── RefreshTokenService.java      ← opaque token issue/rotate/revoke
        ├── SessionService.java           ← session list + revoke (cursor pagination)
        ├── SigningKeyRotationService.java
        └── SigningKeyService.java
```

---

## 3. Security Filter Chain (6 chain)

Semua endpoint di`AuthSecurityConfig`. Tidak ada cookie, tidak ada `oauth2Login`.  
Setiap chain **stateless** + **CSRF disabled**.

| Order | Bean           | Matches                                   | Auth          |
|-------|----------------|-------------------------------------------|---------------|
| 0     | `healthProbe`  | `GET /actuator/health`, `/health/**`     | permitAll     |
| 1     | `wellKnown`    | `/.well-known/jwks.json`                | permitAll     |
| 2     | `publicAuth`   | `/api/v1/auth/register`, `/login`, `/refresh`, `/logout`, `/oauth/**` | permitAll |
| 3     | `api`          | `/api/**`                                 | Bearer JWT; `ADMIN` untuk `/api/v1/admin/**` |
| 4     | `oauthCallback`| `GET /auth/oauth/google/callback`       | permitAll     |
| LOW   | `fallback`     | `/**`                                     | denyAll       |

Chain `api` memakai `DbJwtDecoder` (kunci penandatanganan dari DB) + `JwtAuthConverter` (JWT → `AuthenticatedUser` principal).

Route yang tidak cocok **chain 0‑4** → ditolak oleh `fallback` (return JSON `401`).

---

## 4. Endpoint Summary

### 4.1 Credential (`AuthController`)

| Method | Path | Auth | Body/Params | Response |
|--------|------|------|-------------|----------|
| POST | `/api/v1/auth/register` | none | `{email, password, displayName?}` | `TokenResponse` |
| POST | `/api/v1/auth/login` | none | `{email, password}` | `TokenResponse` |
| POST | `/api/v1/auth/refresh` | none | `{refreshToken}` | `TokenResponse` |
| POST | `/api/v1/auth/logout` | none | `{refreshToken}` | 200 |
| POST | `/api/v1/auth/password` | Bearer | `{newPassword}` | 200 |
| GET  | `/api/v1/auth/me` | Bearer | — | `UserDetailsDto` (`user` = UserDto auth + `profile` dari module user) |
| GET  | `/api/v1/auth/sessions` | Bearer | `X-Refresh-Token`, `cursor`, `limit` | `SessionPage` |
| DELETE | `/api/v1/auth/sessions/{id}` | Bearer | `X-Refresh-Token` | 200 |
| DELETE | `/api/v1/auth/sessions` | Bearer | `X-Refresh-Token` (wajib) | 200 (revoke all except current) |

### 4.2 Google OAuth (`GoogleController`)

| Method | Path | Auth | Params/Body | Response |
|--------|------|------|-------------|----------|
| GET  | `/api/v1/auth/oauth/google` | none | `?redirect_url=...` | `{redirectUrl}` |
| GET  | `/auth/oauth/google/callback` | none | `?state=...&code=...` | `302 ←redirect_url?code=<otc>` |
| POST | `/api/v1/auth/oauth/exchange` | none | `{code}` | `TokenResponse` |

> Callback endpoint **tidak** di bawah `/api/` karena ini redirect dari Google, bukan REST API.

### 4.3 Admin (`AdminController`)

| Method | Path | Auth | Body | Response |
|--------|------|------|------|----------|
| POST | `/api/v1/admin/keys/rotate` | Bearer `ADMIN` | — | `RotatedKeyResponse` |
| PATCH | `/api/v1/admin/users/{userId}/status` | Bearer `ADMIN` | `{status: "ACTIVE"\|"SUSPENDED"\|"DISABLED"}` | 200 (evict cache /me user tsb) |

### 4.4 JWKS (`JwksController`)

| Method | Path | Auth | Response |
|--------|------|------|----------|
| GET | `/.well-known/jwks.json` | none | JWK Set (cached Redis) |

### 4.5 `TokenResponse` structure

```json
{
  "accessToken": "<RS256-signed JWT>",
  "refreshToken": "<opaque-URL-safe-base64>",
  "refreshTokenId": "<UUID v7>",
  "sessionId": "<UUID v7>",
  "user": {
    "userId": "<UUID v7>",
    "email": "<email>",
    "emailVerified": true,
    "status": "ACTIVE",
    "roles": ["USER", "ADMIN"]
  }
}
```

`user` = `auth/api/UserDto` (boundary DTO, aman dipakai modul lain). Auth/authz sendiri ditangani Spring Security via claim `roles` di JWT (`JwtAuthConverter` → `ROLE_*`), bukan dari field `user` ini.
`status` = `UserStatus` (`ACTIVE` | `SUSPENDED` | `DISABLED`) — hanya `ACTIVE` yang boleh autentikasi.

### 4.6 User Profile (module `user`)

Data profil (nama, nickname, avatar, bio, dll) dipegang **module `user`** — schema `user`, tabel `profile`
(1:1 per `auth.users.id`, tanpa FK cross-schema). Arah dependensi: **auth → user** (satu arah, via `user.api.ProfileService`)
sehingga tidak ada cycle module; event `UserRegistered` dipublish oleh module `user` (bukan auth).

| Method | Path | Auth | Body | Response |
|--------|------|------|-------------|----------|
| PATCH | `/api/v1/users/me/profile` | Bearer | `UpdateProfileRequest` | `ApiResponse<UserProfileDto>` |

Semantik PATCH: field `null` = tidak diubah; string kosong = hapus; nickname duplikat → `409 user.nickname_taken`.
Pembacaan profil digabung di `GET /api/v1/auth/me` (field `profile` pada `UserDetailsDto`).

---

## 5. Alur Credential Login

```
POST /api/v1/auth/login {email, password}
    │
    ▼
AuthService.login()
    ├── auth_identities WHERE provider='credentials' AND provider_id=email
    ├── BCrypt.verify(password, hash)
    ├── status gate: hanya status=ACTIVE yang lanjut; SUSPENDED → 403 auth.account_suspended,
    │                DISABLED → 403 auth.account_disabled (kredensial tetap diverifikasi dulu,
    │                supaya status akun tidak bocor ke attacker tanpa password yang benar)
    ├── RoleResolver.effectiveRoles(user) → default USER
    ├── JwtService.issueAccessToken(userId, email, roles)  → RS256, TTL 15m
    ├── RefreshTokenService.issue(userId, device, ip, 30d)
    ├── events.publishEvent(UserAuthenticated)
    └── return TokenResponse(accessToken, refreshToken, ..., user=UserDto)
```

**Register:** mirip — buat `User` + `auth_identities` (provider=credentials), lalu panggil
`user.api.ProfileService.initialize(userId, email, displayName, null)` — module user membuat baris profil
(dan publish event `UserRegistered` dari `user.api`), satu transaksi dengan registrasi.
`displayName` opsional dari form register (blank → null); akun baru selalu berstatus `ACTIVE`.

**Status akun & cache `/auth/me`:**
- Hanya `ACTIVE` yang boleh login (credentials/Google OAuth) **dan** refresh — akun
  `SUSPENDED`/`DISABLED` tidak bisa memulai sesi baru maupun memperpanjang sesi lama
  (AT yang sudah terbit tetap valid sampai expiry ≤15m; tidak ada deny-list AT).
- `GET /auth/me` di-cache di Redis (`cache:user-details:{userId}`, TTL default 10m,
  `app.security.user-details-cache-ttl`) — shared cache aman multi-instance. Evict eksplisit:
  - profil di-update → module user publish `ProfileUpdated` → listener auth evict;
  - status akun diganti (admin) → evict;
  - email terverifikasi via Google login (markEmailVerified) → evict.
- Gagal serialize/deserialize cache = miss (fallback DB), request tidak pernah gagal.

**Refresh:**
- Client mengirim `refreshToken` (opaque, 30d)
- Server: hash(sha256) → cari di `refresh_tokens`
- Rotasi: token lama di-revoke, token baru diterbitkan
- **Reuse detection:** token yang sudah revoked dipakai lagi → `auth.refresh_token_reused` + seluruh sesi pengguna dicabut
- Akun non-ACTIVE ditolak di sini juga (exception → satu transaksi rollback, rotasi batal).

**Logout:** revoke refresh token session (AT tetap valid stateless sampai expiry — tidak ada deny-list)

**Set password:** binding user google-only ke credentials identity (perlu input user saat ini via Bearer AT)

---

## 6. Alur Google OAuth (PKCE Backend)

> Semua dance OAuth di backend — client-id/secret tidak pernah dikirim ke frontend.  
> Mengikuti Authorization Code flow dengan PKCE S256.

### Diagram Alur

```
┌──────┐      ┌─────────────────────┐      ┌──────────┐      ┌──────────┐
│Client│      │ Backend (this app)  │      │  Google  │      │  Redis   │
└──┬───┘      └─────────┬───────────┘      └────┬─────┘      └────┬─────┘
   │                    │                       │                  │
   │ ① GET /api/v1/auth/oauth/google            │                  │
   │  ?redirect_url=https://fe.example.com/cb   │                  │
   │───────────────────▶│                       │                  │
   │                    │──▶ simpan state+       │                  │
   │                    │    PKCE+nonce ──────────────────────────▶│
   │                    │    (TTL 10 menit)     │                  │
   │                    │                       │                  │
   │ ② 200 {redirectUrl}│                       │                  │
   │◀───────────────────│                       │                  │
   │                    │                       │                  │
   │ ③ location.href =  https://accounts.google.com/o/oauth2/v2/auth?
   │    &response_type=code&client_id=...&state=...&code_challenge=...
   │    &code_challenge_method=S256&nonce=...&redirect_uri=...&scope=...
   │───────────────────────────────────────────▶│
   │                    │                       │
   │ ④ Google login form, user consent         │
   │◀───────────────────────────────────────────│
   │                    │                       │
   │ ⑤ GET /auth/oauth/google/callback         │
   │    ?state=...&code=...                    │
   │───────────────────────────────────────────▶│
   │                    │                       │
   │                    │──▶ hapus state ◀──────│
   │                    │──▶ POST token endpoint│
   │                    │    (code+PKCE+secret) ▶│
   │                    │                       │
   │                    │◀── id_token ──────────│
   │                    │                       │
   │                    │──▶ validasi id_token:  │
   │                    │    - signature (JWKS)  │
   │                    │    - issuer (accounts.google.com)
   │                    │    - audience (client_id)
   │                    │    - email_verified
   │                    │    - nonce
   │                    │──▶ AuthService.googleLogin(sub, email)
   │                    │──▶ simpan TokenResponse
   │                    │    sbg oneTimeCode  ────────────────▶│
   │                    │    (TTL 5 menit,     │              │
   │                    │     single‑use)     │              │
   │                    │                       │              │
   │ ⑥ 302  https://fe.example.com/cb?code=<oneTimeCode>
   │◀────────────────────────────────────────────────────────────
   │                    │                       │              │
   │ ⑦ POST /api/v1/auth/oauth/exchange        │              │
   │    {code: "<oneTimeCode>"}                │              │
   │───────────────────▶│                       │              │
   │                    │──▶ hapus OTC ◀───────────────────────│
   │                    │──▶ return TokenResponse              │
   │                    │                       │              │
   │ ⑧ 200 TokenResponse                       │              │
   │◀───────────────────│                       │              │
   │                    │                       │              │
   │ ⑨ Simpan accessToken + refreshToken       │              │
   │    di localStorage / secure storage       │              │
```

### Detil Validasi Keamanan di Callback

Setelah menerima `code` dari Google (langkah ⑤), backend melakukan:

| Validasi | Cara | Gagal → error |
|----------|------|---------------|
| State ada di Redis | `getAndDelete(STATE_PREFIX + state)` | `OAUTH_STATE_INVALID` (anti-CSRF) |
| Token exchange (PKCE) | `POST token_uri` dengan `code_verifier` | `OAUTH_PROVIDER_ERROR` |
| id_token signature | `NimbusJwtDecoder` (Google JWKS) | `JwtException` → `OAUTH_PROVIDER_ERROR` |
| `iss` = `https://accounts.google.com` | `NimbusJwtDecoder.withIssuerLocation(...)` | `JwtException` |
| `aud` = client_id kita | `jwt.getAudience().contains(clientId)` | `OAUTH_PROVIDER_ERROR` |
| `email_verified` = true | `Boolean.TRUE.equals(jwt.getClaim("email_verified"))` | `OAUTH_PROVIDER_ERROR` |
| `nonce` = disimpan di state | `stateData.nonce().equals(jwt.getClaim("nonce"))` | `OAUTH_PROVIDER_ERROR` (anti-replay) |

### Otentikasi Akun (Account Linking)

`AuthService.googleLogin(sub, email, ...)`:

1. Cari `auth_identities` dengan `provider='google'` dan `provider_id=sub`.
2. **Sudah ada** → langsung issue tokens.
3. **Belum ada**:
   - Cari `users` by email.  
     - **Tidak ada** → buat `User` baru + `AuthIdentity(google)`.  
       Panggil `ProfileService.initialize(userId, email, name, picture)` — profil ter-seed dari
       klaim `name`/`picture` id_token Google; module user publish `UserRegistered`.  
     - **Ada** → `markEmailVerified()` + tambah `AuthIdentity(google)` (linking).  
       **Tidak** publish `UserRegistered` dan profil **tidak** di-seed ulang (user sudah ada,
       hanya mengikat identity baru).
4. Issue `TokenResponse` + publish `UserAuthenticated`.

> ⚠️  **PRE-ACCOUNT HIJACKING RISK** — Alur daftar credential **tidak** melakukan verifikasi
> email (`emailVerifiedAt` tetap `null` pada register), dan login credential **tidak** menolak
> user yang email-nya belum diverifikasi. Skenario: attacker daftar dahulu dengan
> `victim@gmail.com` + password attacker (akun belum diverifikasi, tapi sudah bisa login).
> Suatu saat korban login via Google dengan email yang sama → identity Google ditempelkan
> ke akun yang sudah dikuasai attacker (auto‑link by email). Artinya korban memakai akun
> yang attacker-nya tetap bisa login via password—permanen.
>
> Ini dikenal sebagai **pre‑hijacking** (Paverd et al., *Pre‑hijacking Attacks on Web User
> Accounts*). **KEPUTUSAN PROYEK:** dibiarkan untuk MVP / internal tanpa biaya email provider.
> **JANGAN** aktifkan auto‑link‑by‑email di produksi publik tanpa verifikasi email DAHULU.

---

## 7. Enkripsi & Kunci

| Komponen | Deskripsi |
|----------|-----------|
| **Password user** | BCrypt (via `PasswordHasher`) |
| **Private key signing key** | AES-256-GCM terenkripsi di DB (ciphertext = `[12‑byte IV] + [ciphertext + tag]`) |
| **Master key** | Base64 32-byte, dari env `MASTER_KEY_CURRENT` |
| **Rotasi master key** | `MASTER_KEY_PREVIOUS` (env) → `MasterKeyRotationJob` (Quartz) re‑encrypt semua signing key |
| **Rotasi signing key** | `SigningKeyRotationJob` (Quartz) → generate RSA 2048 baru, status `ACTIVE`; key lama → `PREVIOUS` → `RETIRED` |
| **Initial seeding** | `SigningKeySeeder` (ApplicationRunner) membuat signing key pertama saat startup bila belum ada |

JWT ditandatangani dengan RSA `RS256`. Header JWT menyertakan `kid` = UUID signing key.

`DbJwtDecoder` memuat semua kunci `ACTIVE` + `PREVIOUS` dari DB saat membangun decoder — ini memungkinkan verifikasi token yang ditandatangani oleh kunci sebelumnya (selama masa transisi rotasi).

---

## 8. Database Schema

Semua tabel di schema `auth`. **Tidak ada foreign key cross-schema.**

| Tabel | Kolom utama | Note |
|-------|-------------|------|
| `users` | `id`, `email` (unique), `email_verified_at`, `status`, `status_changed_at`, `created_at`, `updated_at` | 1 user bisa punya banyak identity (multi‑provider); `status` = `ACTIVE`/`SUSPENDED`/`DISABLED` (CHECK constraint) |
| `auth_identities` | `id`, `user_id`, `provider`, `provider_id`, `email`, `password_hash`, `created_at` | `provider` = `'credentials'` atau `'google'`; `provider_id` untuk credentials = email, untuk google = Google sub |
| `signing_keys` | `kid`, `public_key`, `private_key_cipher`, `enc_key_id`, `algorithm`, `status`, `not_before`, `not_after` | partial unique index: **hanya 1 `ACTIVE`** |
| `refresh_tokens` | `id`, `session_id`, `user_id`, `token_hash`, `device_info`, `ip_address`, `status`, `expires_at`, `issued_at` | `status` = `ACTIVE`/`REVOKED`; composite index `(user_id, issued_at DESC, id DESC)` untuk cursor pagination |
| `roles` | `id`, `role` | Enum: `ADMIN`, `USER` |
| `user_roles` | `user_id`, `role` | Join table (ElementCollection) |

### Migration files (flat `db/migration/`)

```
V1__event_publication.sql          — platform: modulith event pub
V2__quartz_tables.sql              — platform: Quartz JDBC store
V3__auth_signing_keys.sql          — auth schema + signing_keys
V4__auth_users.sql                 — users + auth_identities
V5__auth_refresh_tokens.sql        — refresh_tokens + composite index
V6__auth_roles.sql                 — roles + user_roles
V7__user_profile.sql               — module user: schema "user" + tabel profile
V8__auth_user_status.sql           — auth: kolom status + status_changed_at + CHECK constraint
```

---

## 9. Session Management

- **1 login = 1 session** (1 refresh token = 1 session record).
- Refresh token bersifat **opaque** (SHA‑256 hash‑nya disimpan, raw token tidak pernah di‑persist).
- Pada rotasi (refresh), token lama di‑revoke, token baru diterbitkan **dengan sessionId yang sama** — sehingga rotasi tidak membuat session baru.
- **Reuse detection:** token yang sudah revoked dipakai lagi → seluruh sesi user di‑revoke (security measure).
- **Session listing:** `GET /api/v1/auth/sessions` menggunakan **cursor/keyset pagination** (`WHERE (issued_at, id) < (?, ?) ORDER BY issued_at DESC, id DESC`), composite indexed.
- **Revoke:** client mengirim session saat ini (`X-Refresh-Token`) — session tersebut **tidak bisa direvoke** dari dirinya sendiri.
- **Access token tetap valid sampai expiry** (stateless) — setelah session di‑revoke, AT lama tidak bisa dipakai untuk refresh (RT sudah di‑revoke).

---

## 10. Pesan i18n

File: `src/main/resources/i18n/auth/messages.properties` dan `messages_id.properties`.

| Key | English | Indonesian |
|-----|---------|------------|
| `auth.invalid_credentials` | Invalid email or password | Email atau password tidak valid |
| `auth.account_suspended` | Account suspended. Contact support for more information. | Akun ditangguhkan. Hubungi dukungan untuk informasi lebih lanjut. |
| `auth.account_disabled` | Account disabled. Contact support for more information. | Akun dinonaktifkan. Hubungi dukungan untuk informasi lebih lanjut. |
| `auth.user_status_updated` | User status updated successfully | Status user berhasil diperbarui |
| `auth.token_expired` | Access token has expired | Access token telah kedaluwarsa |
| `auth.token_invalid` | Invalid access token | Access token tidak valid |
| `auth.token_revoked` | Access token has been revoked | Access token telah dicabut |
| `auth.token_missing` | Access token is missing | Access token tidak ditemukan |
| `auth.refresh_token_expired` | Refresh token has expired | Refresh token telah kedaluwarsa |
| `auth.refresh_token_revoked` | Refresh token has been revoked | Refresh token telah dicabut |
| `auth.refresh_token_reused` | Refresh token reuse detected | Refresh token digunakan ulang — semua sesi dicabut |
| `auth.current_token_required` | Current refresh token is required | Refresh token saat ini wajib dikirim |
| `auth.cannot_revoke_current` | You cannot revoke the current session | Tidak bisa mencabut session yang sedang dipakai |
| `auth.session_not_found` | Session not found | Session tidak ditemukan |
| `auth.email_already_linked` | Email already linked | Email ini sudah tertaut ke akun lain |
| `auth.identity_exists` | Identity already exists | Identitas sudah ada untuk akun ini |
| `auth.identity_not_found` | Identity not found | Identitas tidak ditemukan |
| `auth.key_generation_failed` | Failed to generate signing key | Gagal menghasilkan kunci penandatanganan |
| `auth.key_not_found` | Signing key not found | Kunci penandatanganan tidak ditemukan |
| `auth.encryption_failed` | Failed to encrypt private key | Gagal mengenkripsi kunci privat |
| `auth.decryption_failed` | Failed to decrypt private key | Gagal mendekripsi kunci privat |
| `auth.master_key_invalid` | Master encryption key invalid | Kunci enkripsi master tidak valid |
| `auth.oauth_state_invalid` | OAuth state invalid or expired | State OAuth tidak valid atau sudah kedaluwarsa |
| `auth.oauth_code_reused` | One-time code already used or expired | Kode sekali pakai sudah digunakan atau kedaluwarsa |
| `auth.oauth_redirect_forbidden` | Redirect URL not in allowed list | URL redirect tidak termasuk dalam daftar yang diizinkan |
| `auth.oauth_provider_error` | Failed to authenticate with Google | Gagal autentikasi dengan Google |
| `auth.login_success` | Login successful | Login berhasil |
| `auth.refresh_success` | Token refreshed successfully | Token berhasil diperbarui |
| `auth.logout_success` | Logout successful | Logout berhasil |
| `auth.password_set_success` | Password set successfully | Password berhasil diatur |
| `auth.sessions_revoked_success` | Selected sessions revoked | Session terpilih telah dicabut |
| `auth.keys_rotated_success` | Signing keys rotated | Kunci penandatanganan berhasil dirotasi |

---

## 11. Environment Variables

| Variable | Required | Default | Deskripsi |
|----------|----------|---------|-----------|
| `MASTER_KEY_CURRENT` | **Ya** | — | Base64 32‑byte AES‑256 key. Profil `test` memakai default dev-key di `src/test/resources/application-test.yaml` (env var tetap menang) |
| `MASTER_KEY_PREVIOUS` | Tidak | — | Key sebelumnya (untuk rotasi) |
| `GOOGLE_CLIENT_ID` | Ya (OAuth) | — | Google Cloud Console client ID |
| `GOOGLE_CLIENT_SECRET` | Ya (OAuth) | — | Google Cloud Console client secret |
| `APP_SECURITY_OAUTH_REDIRECT_URI` | Tidak | `http://localhost:8080/auth/oauth/google/callback` | Callback URI (harus terdaftar di Google Console) |
| `APP_SECURITY_OAUTH_FRONTEND_REDIRECT_URIS` | Tidak | `http://localhost:3000` | Origin frontend yang diizinkan (koma-separated) |
| `APP_SECURITY_OAUTH_ONE_TIME_CODE_TTL` | Tidak | `5m` | Masa berlaku one‑time code (Duration) |
| `APP_SECURITY_RATE_LIMIT_BASE` | Tidak | `1m` | Base delay exponential backoff login (Duration) |
| `APP_SECURITY_RATE_LIMIT_MAX` | Tidak | `1h` | Max delay cap exponential backoff (Duration) |
| `APP_SECURITY_RATE_LIMIT_MAX_ATTEMPTS` | Tidak | `5` | Jumlah percobaan sebelum lockout |
| `APP_SECURITY_ISSUER` | Tidak | `http://localhost:8080` | Issuer claim di JWT |
| `APP_SECURITY_ACCESS_TOKEN_TTL` | Tidak | `15m` | Access token time‑to‑live |
| `APP_SECURITY_REFRESH_TOKEN_TTL` | Tidak | `30d` | Refresh token time‑to‑live |
| `APP_SECURITY_USER_DETAILS_CACHE_TTL` | Tidak | `10m` | TTL cache Redis GET /auth/me (Duration) |
| `SPRING_DATASOURCE_*` | Ya | `jdbc:postgresql://localhost:5432/app` | PostgreSQL connection |
| `SPRING_DATA_REDIS_*` | Ya | `localhost:6379` | Redis connection |

---

## 12. Cara Menambah Aplikasi Web Baru

Tidak perlu redeploy kode — cukup tambah origin frontend ke whitelist:

1. Edit `.env`:
   ```
   APP_SECURITY_OAUTH_FRONTEND_REDIRECT_URIS=https://fe.example.com,https://newapp.example.com,http://localhost:3000
   ```
2. Restart aplikasi.

Validasi menggunakan perbandingan origin (`scheme://host:port`) — bukan string `startsWith` — sehingga
`https://fe.example.com.evil.com` **tidak** akan lolos.

---

## 13. OAuth Provider Lain di Masa Depan

Karena arsitektur sudah modular dengan `auth_identities`:

1. Daftarkan provider baru di `application.yaml` (`spring.security.oauth2.client.registration.<provider>`).
2. Buat `XxxOAuthService` (mirip `GoogleOAuthService`) — PKCE + token exchange + validasi id_token.
3. Panggil `authService.googleLogin()` (atau buat method generik `oauthLogin(provider, sub, email)` bila perlu).
4. Tambah konstanta provider di `AuthIdentity` (e.g. `PROVIDER_GITHUB = "github"`).
5. Tambah endpoint baru di controller atau tambah route ke controller yang sudah ada.

---

## 14. Catatan Keamanan

| Mekanisme | Implementasi |
|-----------|-------------|
| **Password hashing** | BCrypt via `PasswordHasher` |
| **JWT signing** | RS256 (RSA 2048), cert di DB terenkripsi AES‑GCM |
| **Token lifetime** | AT 15 menit, RT 30 hari (configurable) |
| **Refresh rotation** | Setiap refresh → token lama di‑revoke, baru diterbitkan |
| **Reuse detection** | Token revoked dipakai lagi → seluruh sesi user dicabut |
| **PKCE S256** | code_verifier 64 byte random, code_challenge = SHA‑256 |
| **State (OAuth CSRF)** | UUID v7, single‑use (dihapus saat callback), TTL 10 menit |
| **Nonce (OAuth replay)** | UUID v7, divalidasi di id_token |
| **Audience validation** | `aud` di id_token Google harus cocok client_id |
| **Email verification** | `email_verified` claim harus `true` sebelum email dipercaya |
| **Redirect whitelist** | Origin‑based (`URI.getHost()+getPort`) — bukan `startsWith` |
| **One‑time code** | 32 byte random, single‑use (dihapus saat exchange), TTL 5 menit |
| **No‑deny‑list AT** | Access token **tidak** bisa di‑revoke sebelum expiry; trade‑off terima — perpendek `access‑token‑ttl` bila perlu |
| **Account status gate** | Login credentials/OAuth/refresh hanya untuk `status=ACTIVE`; `SUSPENDED`/`DISABLED` → 403 (kredensial dicek dulu, anti-enumeration) |
| **Cache /me** | Redis shared (multi-instance), TTL + evict eksplisit (profil/status/email-verified) |
| **Master key** | Dari env, tidak pernah di‑commit ke git |
| **Private key signing** | Terenkripsi (AES‑GCM) di DB, tidak plaintext |
| **No cross‑schema FK** | Data antar modul direferensi via ID, tidak via constraint database |
| **Rate limit login/register** | Per email, exponential backoff (base 1m, max 1h, 5 attempts), Redis, hapus saat sukses |

> ⚠️  **Credential login tidak mewajibkan verifikasi email.** Register langsung bisa login.
> **Auto‑link Google‑by‑email** tetap aktif walaupun email belum diverifikasi — lihat §6 (Account Linking)
> untuk penjelasan risiko **pre‑account hijacking**.
