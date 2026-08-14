# Admin Module — Panduan

> Modul **control-plane** untuk administrasi platform: manajemen user (status & role),
> rotasi kunci penandatanganan, dan audit trail.
>
> Modul ini TIDAK memiliki data user/role/kunci — data tersebut tetap milik module `auth`
> (single writer). Module `admin` mengorkestrasi lewat kontrak `auth.api`, dan memiliki
> datanya sendiri: **audit log** (schema `admin`).

---

## 1. Kenapa Modul Sendiri?

Sebelumnya admin menumpang di `auth/internal/delivery/http/AdminController.java` + `AuthService.changeStatus`.
Itu mencampur dua bounded context:

| Context | Milik module |
|---|---|
| Autentikasi (login, JWT, kunci, sesi) | `auth` |
| Administrasi platform (user ops, key ops, audit) | `admin` |

Pemisahan ini membuat `auth` fokus, dan `admin` bisa berkembang (mis. jadi microservice
admin terpisah) tanpa menyentuh auth — persis pola AGENTS.md §8.

**Aturan kunci:** module `admin` HANYA boleh memanggil `auth.api` (interface sync).
Dilarang import `auth.internal.*` (dijaga `ModularityTests`).

```
com.gepe.app.admin/
├── api/                                  ← named interface (kosong untuk sekarang)
└── internal/
    ├── delivery/http/                    ← AdminUserController, AdminKeyController, AdminAuditController
    ├── service/                          ← AdminUserService, AdminKeyService, AdminAuditService
    ├── entity/AdminAuditLog.java
    ├── repository/AdminAuditLogRepository.java
    └── dto/                              ← UpdateUserStatusRequest, AssignRolesRequest, AdminAuditLogDto
```

Kontrak `auth.api` yang dipakai:

- `UserAdminService` — listUsers (cursor), findUserDetail, changeStatus, assignRoles
- `KeyManagementService` — rotateSigningKey, listSigningKeys

---

## 2. Endpoint Summary

Semua endpoint butuh Bearer JWT + role `ADMIN` (di enforce 2 lapis:
URL matcher di `AuthSecurityConfig` chain 3 + `@PreAuthorize("hasRole('ADMIN')")`).

### 2.1 Users (`AdminUserController`)

| Method | Path | Body/Params | Deskripsi |
|--------|------|-------------|-----------|
| GET | `/api/v1/admin/users` | `?cursor&limit&status` | List user (keyset pagination by `created_at DESC, id DESC`; filter status opsional) |
| GET | `/api/v1/admin/users/{userId}` | — | Detail user (status, roles, timestamps) |
| PATCH | `/api/v1/admin/users/{userId}/status` | `{status: "ACTIVE"\|"SUSPENDED"\|"DISABLED"}` | Ganti status akun |
| PUT | `/api/v1/admin/users/{userId}/roles` | `{roles: ["USER","ADMIN","OPERATION"]}` | Replace penuh set role |

### 2.2 Keys (`AdminKeyController`)

| Method | Path | Deskripsi |
|--------|------|-----------|
| GET | `/api/v1/admin/keys` | Daftar signing key (ACTIVE/PREVIOUS/RETIRED, terbaru dulu) |
| POST | `/api/v1/admin/keys/rotate` | Rotasi signing key — **runtime, tanpa deploy ulang** |

### 2.3 Audit Logs (`AdminAuditController`)

| Method | Path | Deskripsi |
|--------|------|-----------|
| GET | `/api/v1/admin/audit-logs` | `?cursor&limit` — audit trail (keyset pagination) |

Setiap aksi mutasi menulis 1 baris audit otomatis:

| Action | Target type |
|--------|-------------|
| `USER_STATUS_CHANGED` | `USER` |
| `USER_ROLES_CHANGED` | `USER` |
| `SIGNING_KEY_ROTATED` | `SIGNING_KEY` |

---

## 3. Alur Operasi

### 3.1 Rotasi Signing Key (RSA) — runtime

```
POST /api/v1/admin/keys/rotate  (ADMIN)
    │
    ▼
AdminKeyService.rotateSigningKey(actorId)
    ├── auth.api.KeyManagementService.rotateSigningKey()
    │       └── SigningKeyRotationService.rotate()      (module auth, satu transaksi)
    │           ├── key ACTIVE lama → PREVIOUS (not_after = now + 1 jam)
    │           ├── PREVIOUS kedaluwarsa → RETIRED
    │           ├── generate RSA 2048 baru → ACTIVE (unique: hanya 1 ACTIVE)
    │           └── delete cache JWKS Redis
    ├── audit: SIGNING_KEY_ROTATED (payload: kid, status, notBefore)
    └── return RotatedKeyResponse
```

- **Tanpa deploy ulang** — cukup klik/API. Berlaku langsung untuk semua instance.
- Key PREVIOUS tetap bisa memverifikasi JWT lama selama 1 jam (overlap window),
  lalu di-RETIRED otomatis oleh job Quartz berikutnya.
- Tidak ada downtime: JWT baru ditandatangani key ACTIVE baru, JWT lama masih valid
  sampai 1 jam / sampai key lama RETIRED.

### 3.2 Ganti Status Akun (Suspend / Disable / Activate)

```
PATCH /api/v1/admin/users/{userId}/status  (ADMIN)
    │
    ▼
AdminUserService.changeStatus(actorId, userId, status)
    ├── auth.api.UserAdminService.changeStatus(actorId, userId, status)
    │       ├── guard: actorId ≠ userId (tidak bisa suspend diri sendiri)
    │       ├── user.changeStatus(status) + save
    │       └── evict cache Redis /auth/me user tsb
    ├── audit: USER_STATUS_CHANGED (payload: status)
    └── 200
```

Efek ke user target:
- `SUSPENDED`/`DISABLED` → tidak bisa login credentials/OAuth/refresh
  (ditolak di `AuthService.assertLoginAllowed` dengan pesan per status).
- Access token yang sudah terbit tetap valid sampai expiry (≤15m) — tidak ada deny-list AT.

### 3.3 Assign Role

```
PUT /api/v1/admin/users/{userId}/roles  (ADMIN)
    │
    ▼
AdminUserService.assignRoles(actorId, userId, roles)
    ├── auth.api.UserAdminService.assignRoles(actorId, userId, roles)
    │       ├── validasi: roles tidak kosong & semua ada di auth.roles
    │       ├── guard: actorId ≠ userId
    │       ├── guard: tidak boleh cabut ADMIN dari admin terakhir
    │       ├── replace penuh set role user
    │       ├── evict cache /me
    │       └── revoke SEMUA refresh token user  ← privilege change
    ├── audit: USER_ROLES_CHANGED (payload: roles baru)
    └── 200
```

Kenapa sesi di-revoke? Role di-bake ke claim JWT saat issue/refresh. Tanpa revoke,
user yang di-demote tetap punya role lama sampai AT expiry. Dengan revoke semua sesi:
- Refresh token hangus → user harus login ulang → JWT baru membawa roles baru.
- AT lama tetap valid ≤15m (trade-off no-deny-list AT yang sudah dipilih proyek).

---

## 4. Audit Log (schema `admin`)

```sql
CREATE TABLE admin.admin_audit_logs (
    id            UUID         NOT NULL,   -- UUID v7
    actor_user_id UUID         NOT NULL,   -- = auth.users.id (tanpa FK cross-schema)
    action        VARCHAR(50)  NOT NULL,
    target_type   VARCHAR(30)  NOT NULL,
    target_id     VARCHAR(64),
    payload       JSONB,                   -- detail aksi
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);
CREATE INDEX idx_admin_audit_logs_created ON admin.admin_audit_logs (created_at DESC, id DESC);
```

- **Append-only** — tidak ada update/delete.
- Ditulis dalam **transaksi yang sama** dengan mutasi (propagation REQUIRED) → atomik.
- Payload JSON diserialisasi dengan ObjectMapper; gagal serialize hanya log warning
  (audit tidak boleh memblokir aksi admin).

---

## 5. Keamanan & Guard

Hierarki role: **SUPER_ADMIN > ADMIN > OPERATION > USER** (rank 3/2/1/0).

| Guard | Lokasi | Hasil |
|-------|--------|-------|
| Endpoint admin butuh role ADMIN | `AuthSecurityConfig` chain 3 + `@PreAuthorize` | 401/403 |
| Akun biasa tidak bisa mengubah dirinya sendiri | `UserAdminServiceImpl.resolveActor` | 403 `admin.self_operation_forbidden` |
| **Hierarki** — hanya bisa mengubah akun dengan rank di bawah | `UserAdminServiceImpl.resolveActor` | 403 `admin.insufficient_privilege` |
| **Protected** — tidak ada yang bisa mengubah SUPER_ADMIN selain akun itu sendiri (pengecualian self untuk SUPER_ADMIN) | `UserAdminServiceImpl.resolveActor` | 403 |
| Grant — tidak bisa memberi role rank ≥ rank sendiri; hanya SUPER_ADMIN yang bisa memberi SUPER_ADMIN | `UserAdminServiceImpl.assertCanGrant` | 403 `admin.insufficient_privilege` |
| Tidak bisa cabut role ADMIN dari admin terakhir | `UserAdminServiceImpl.assignRoles` | 409 `admin.last_admin_removal` |
| Tidak bisa cabut role SUPER_ADMIN dari pemegang terakhir | `UserAdminServiceImpl.assignRoles` | 409 `admin.last_super_admin_removal` |
| Tidak bisa suspend/disable SUPER_ADMIN aktif terakhir | `UserAdminServiceImpl.changeStatus` | 409 `admin.last_super_admin_status` |
| Role tidak dikenal | `UserAdminServiceImpl.assignRoles` | 400 `auth.role_not_found` |
| Set role kosong | DTO `@NotEmpty` + service | 400 |
| User tidak ditemukan | `UserAdminServiceImpl` | 404 `auth.user_not_found` |
| Cursor pagination rusak | `UserAdminServiceImpl` / `AdminAuditService` | 400 / empty page |

**Akibat praktis hierarki:**
- ADMIN tidak bisa mengubah ADMIN lain (suspend/demote) — hanya SUPER_ADMIN yang bisa.
- Akun SUPER_ADMIN adalah **protected**: tidak bisa diubah oleh siapa pun kecuali dirinya
  sendiri (termasuk tidak bisa diubah SUPER_ADMIN lain).
- SUPER_ADMIN bisa membuat SUPER_ADMIN baru (grant role), tapi setelah itu akun baru
  tersebut juga protected — tidak bisa dicabut lewat API (hanya via SQL). Ini memang
  konsekuensi dari desain "root protected".

---

## 5a. Membuat Akun SUPER_ADMIN (Manual via Database)

Keputusan desain: akun SUPER_ADMIN **tidak dibuat via seeder/app code** — dibuat manual
di database. Migration hanya men-seed *role*-nya (di `V6__auth_roles.sql`),
bukan akunnya.

**Langkah:**

1. Generate BCrypt hash password (sekali pakai):
   ```bash
   htpasswd -bnBC 10 "" 'PasswordRahasia123!' | tr -d ':\n'
   # output: $2a$10$... — salin ke password_hash
   ```

2. Insert 3 baris (satu transaksi):
   ```sql
   BEGIN;

   -- user (id: pakai UUID v7 — bisa disalin dari baris users lain / generator v7;
   --        gen_random_uuid() hanya fallback terakhir, hasilnya v4)
   INSERT INTO auth.users (id, email, email_verified_at, status, status_changed_at, created_at, updated_at)
   VALUES ('0190eaaa-0000-7000-8000-0000000000ff', 'root@domain.com', now(), 'ACTIVE', now(), now(), now());

   -- identity login email+password
   INSERT INTO auth.auth_identities (id, user_id, provider, provider_id, email, password_hash, created_at)
   VALUES ('0190eaaa-0000-7000-8000-000000000100', '0190eaaa-0000-7000-8000-0000000000ff',
           'credentials', 'root@domain.com', 'root@domain.com', '$2a$10$...', now());

   -- role SUPER_ADMIN
   INSERT INTO auth.user_roles (user_id, role)
   VALUES ('0190eaaa-0000-7000-8000-0000000000ff', 'SUPER_ADMIN');

   COMMIT;
   ```

   > Catatan: tidak ada baris `user.profile` — itu aman, `GET /auth/me` mengembalikan
   > `profile: null`. Kalau mau, buat juga baris profil di schema `"user"`.

3. Login dengan email + password tersebut. Akun ini sekarang protected.

> ⚠️ **Recovery:** karena akun SUPER_ADMIN hanya bisa diubah oleh dirinya sendiri,
> satu-satunya jalan keluar (mis. lupa password / salah suspend) adalah via SQL langsung
> ke `auth.users.status` / `auth.user_roles`. Jangan pernah menonaktifkan SUPER_ADMIN
> terakhir lewat DB tanpa sengaja.

---

## 6. Master Key — tetap env-based

Keputusan desain: master key TIDAK ikut ke module admin dan TIDAK bisa dirotasi via API.
Alasan: jarang berubah, dan menyimpannya di DB menambah kompleksitas (muter-muter)
tanpa keuntungan yang sepadan.

Rotasi master key tetap seperti sebelumnya — **env + restart**:

1. Set `MASTER_KEY_PREVIOUS` = key lama, `MASTER_KEY_CURRENT` = key baru (base64 32-byte).
2. Restart aplikasi.
3. `MasterKeyRotationJob` (Quartz) re-encrypt semua signing key dari key lama ke baru.

---

## 7. Frontend

Route admin di bawah `/_authenticated/admin`:

| Route | Isi |
|-------|-----|
| `/admin` | List user + filter status + pagination cursor |
| `/admin/users/$userId` | Detail user: ganti status, assign roles (checkbox) |
| `/admin/keys` | List signing key + tombol rotate |
| `/admin/audit-logs` | Audit trail + pagination |

- Guard: `requireAdminMiddleware` (server) + `beforeLoad` di layout `/admin`
  (cek `session.user.roles` mengandung `ADMIN`) → redirect `/dashboard` jika bukan admin.
- Link "Admin" di dashboard hanya muncul untuk role ADMIN.
- Semua server function admin memakai `authedApi.request` (silent refresh otomatis).

---

## 8. Migration Files

```
V8__admin_audit_logs.sql          — schema admin + admin_audit_logs + index keyset
```

> Migration lain yang relevan: `V4__auth_users.sql` (users + status + CHECK + index
> filter status) dan `V6__auth_roles.sql` (seed role incl. SUPER_ADMIN). Masih dev —
> semua "update table" sudah digabung ke file pembuatannya, tidak ada file migration
> khusus update.

---

## 9. Cara Mencoba (Postman)

Collection: `backend/SPRING STARTER.postman_collection.json` — folder **ADMIN**.

**Prasyarat — akun ADMIN.** Akun baru (register/login) selalu ber-role `USER` dan
belum ada UI/endpoint untuk mempromosikan akun sendiri (guard: tidak bisa ubah role
sendiri). Untuk mencoba, promosikan akun via SQL langsung (sekali saja):

```sql
-- 1. cari id user
SELECT id, email FROM auth.users WHERE email = 'email@kamu.com';

-- 2. beri role ADMIN (atau SUPER_ADMIN untuk akun root protected — lihat §5a)
INSERT INTO auth.user_roles (user_id, role) VALUES ('<uuid-dari-atas>', 'ADMIN');
```

> Untuk menguji hierarki: promosikan satu akun jadi `ADMIN` dan satu akun jadi
> `SUPER_ADMIN`, lalu coba ADMIN mengubah ADMIN lain → harus ditolak 403.

**Alur coba:**
1. **Login** (folder auth) → otomatis menyimpan `access_token`.
2. **list users** (folder ADMIN) → otomatis mengisi variabel `userId` dari user pertama.
3. Coba: **get user detail**, **update user status** (SUSPENDED → login user tsb ditolak),
   **assign roles** (user tsb dipaksa login ulang — semua sesinya di-revoke),
   **list signing keys** + **rotate rsa** (runtime, tanpa restart),
   **list audit logs** (semua aksi tadi tercatat di sini).

> Catatan: jangan suspend/disable akun admin yang sedang kamu pakai untuk mencoba —
> guard self-operation hanya mencegah aksi pada akun sendiri via API, tapi aksi SQL di
> atas tetap bisa mengunci akunmu sendiri (promosikan/aktifkan lagi via SQL).
