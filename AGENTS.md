# AGENTS.md

> Architectural contract. Every AI assistant and developer **MUST** read and follow
> these rules before writing code.
>
> Goal: modular monolith — easy to scale multi-instance, easy to extract as microservice
> per module — without rewriting architecture from scratch.

---

## Build & Test

```bash
./mvnw compile                                    # compile
./mvnw test                                       # run all tests
./mvnw test -Dtest="AuthApplicationTests"         # single test
./mvnw test -Dtest="ModularityTests"              # modulith boundary check
./mvnw spring-boot:run                            # start app
./mvnw package -DskipTests                        # build JAR, skip tests
```

- Always use `./mvnw` (Maven wrapper), never system `mvn`.
- **Java 25 is required.** `JAVA_HOME` must point to a JDK 25 (temurin) installation.

---

## Docker / Database

- `compose.yaml` defines a Postgres container (`app`/`root`/`root`). `spring-boot-docker-compose` is disabled
  (`spring.docker.compose.enabled=false`) — the app connects to the externally-managed PostgreSQL on localhost:5432.
- Datasource is configured in `application.yaml` pointing to `jdbc:postgresql://localhost:5432/app`.

---

## Flyway

- **All migrations live flat** at `classpath:db/migration/` with **prefix per module** on file names.
  Platform/infra migrations use no prefix; module migrations use `<module>_` prefix.
  Example flat layout:
    ```
    db/migration/
    ├── V1__event_publication.sql           ← platform (no prefix)
    ├── V2__quartz_tables.sql               ← platform (no prefix)
    ├── V3__auth_signing_keys.sql           ← module auth
    ├── V4__auth_users.sql                  ← module auth
    ├── V5__auth_refresh_tokens.sql          ← module auth
    ├── V6__notification_subscriptions.sql   ← module notification
    ```
- **Version numbers are global across all modules** because Flyway 12.x shares a single version namespace
  regardless of `locations`. Every migration file must have a unique version number, even across modules.
- `spring.flyway.locations` is simply `classpath:db/migration` — no subdirectories needed.
- When adding a new module, append migrations with the next global version number and module prefix
  (e.g. `V6__notification_*.sql`).

---

## Spring Security

- `spring-boot-starter-security` is on the classpath with a custom `SecurityFilterChain` bean (bearer-only JWT).
  All endpoints are secured stateless via JWT Bearer token.
- `spring-boot-starter-oauth2-resource-server` is on the classpath for JWT decoding.
  There is no OAuth2 client registration configured — OAuth dance is handled by the frontend BFF.

---

## 1. Project Context

- Stack: Spring Boot 4.1.0 + Spring Modulith 2.1.0 + PostgreSQL + Flyway + Quartz.
- Pattern: **Modular Monolith**. Each module = 1 bounded context (e.g. `user`, `notification`, `billing`).
- End goal: every module *can* be split into its own microservice without major refactoring.
- Database: **1 database, many schemas** (1 schema = 1 module). Not 1 DB per module (not needed at this stage).

---

## 2. Package Structure (MANDATORY)

`internal/` is now **MANDATORY** for every module (not optional) — it's the single place that groups all implementation
detail, so `api/` (public contract) and `internal/`
(everything else) are never ambiguous at a glance.

```
com.gepe.app
├── AuthApplication.java
├── <module-name>/                          ← root package = 1 module (example: "auth")
│   ├── api/                                ← ONLY public types live here
│   │   ├── AuthApi.java                    ← public interface, for inter-module SYNC calls
│   │   ├── UserRegistered.java             ← public event record, for inter-module ASYNC calls
│   │   └── AuthResult.java                 ← public DTO returned by AuthApi (never an entity)
│   └── internal/                           ← MANDATORY. All implementation detail. Package-private.
│       ├── entity/
│       │   ├── User.java                   ← @Entity, schema = "auth"
│       │   └── RefreshToken.java
│       ├── repository/
│       │   ├── UserRepository.java         ← Spring Data JPA, returns entities (internal only)
│       │   └── RefreshTokenRepository.java
│       ├── service/
│       │   ├── AuthService.java            ← use-case orchestration; converts entity → DTO at
│       │   │                                  the boundary; publishes events via
│       │   │                                  ApplicationEventPublisher (no separate "publisher"
│       │   │                                  class needed — publishing is part of the use case)
│       │   └── UserService.java
│       ├── jwt/
│       │   ├── JwtService.java             ← sign / verify / parse tokens
│       │   ├── JwtProperties.java          ← @ConfigurationProperties
│       │   └── JwtAuthFilter.java          ← OncePerRequestFilter
│       ├── crypto/
│       │   ├── PasswordHasher.java         ← wraps PasswordEncoder
│       │   └── SigningKeyRotationService.java
│       ├── listener/
│       │   └── OtherModuleEventListener.java  ← @ApplicationModuleListener; consumes events
│       │                                        FROM other modules (idempotent!)
│       ├── job/
│       │   ├── RefreshTokenCleanupJob.java      ← Quartz `Job` impl — the actual work
│       │   └── RefreshTokenCleanupScheduler.java ← registers JobDetail/Trigger beans — the schedule
│       ├── dto/
│       │   └── LoginCommand.java           ← internal request/response records used inside the module
│       ├── exception/
│       │   └── InvalidCredentialsException.java
│       ├── config/
│       │   └── AuthSecurityConfig.java
│       └── delivery/
│           └── http/
│               └── AuthController.java     ← REST controller (HTTP entry point). package-private.
│                                           ← depends only on internal/service/ + internal/dto/
├── platform/                   ← shared infrastructure module. Exposable to all modules.
│   │                               Declared via @Modulith(sharedModules = "platform") on AuthApplication.
│   │                               Types meant for cross-module use may be public (shared modules bypass named-interface enforcement).
│   ├── modulith/                ← modulith-specific infra (quartz event resubmission scheduler, etc.)
│   └── support/Uuidv7.java      ← THE ONLY allowed UUID generator (generates UUID v7)
```

**Sub-package rules inside `internal/`:**

1. `entity/` and `repository/` never leave `internal/` — no other package (not even another sub-package of the same
   module) should treat a `Repository` return value as public API;
   `service/` is the only caller.
2. `service/` is the only place allowed to call `ApplicationEventPublisher.publishEvent(...)`. There is deliberately
   **no `publisher/` package** — publishing an event is one line inside the use case that already owns the transaction,
   not a separate abstraction.
3. `listener/` is only for **incoming** events (`@ApplicationModuleListener`) — i.e. this module reacting to another
   module's event. A module's own domain listeners (reacting to its own events) also belong here.
4. `job/` (the Quartz `Job`, business logic) is always split from `scheduler/`-suffixed classes (the `JobDetail`/
   `Trigger` registration). Keeps "what runs" separate from "when it runs" — e.g. `RefreshTokenCleanupJob` vs
   `RefreshTokenCleanupScheduler`.
5. `jwt/` and `crypto/` (or any other tech-specific concern) get their own sub-package once a module has more than 1–2
   classes for that concern — don't dump them in `service/`.
6. `dto/` holds records used **within** the module (service ↔ delivery/http). DTOs exposed to *other* modules go in
   `api/` instead — don't duplicate the same record in both places.
7. `delivery/http/` is for REST controllers ONLY — no business logic, no entity/repository access. Controllers are
   package-private and depend only on `internal/service/` (plus `internal/dto/` records). A controller is **NEVER**
   placed in `api/` — `api/` is for other *modules* to call, not for HTTP entry points.

## UUID Rule (MANDATORY)

1. **ALL UUIDs are UUID v7** — everywhere: entity IDs, request IDs, event IDs, correlation IDs, etc.
2. Generate them ONLY via the shared helper `com.gepe.app.platform.support.Uuidv7.generate()`.
3. **FORBIDDEN**: `UUID.randomUUID()` (v4) and `UUID.nameUUIDFromBytes()` (v3) anywhere in the codebase.
4. Reason: v7 is time-ordered → index-friendly (no B-tree leaf churn), and sortable/range-queryable — critical for
   `ddl-auto: validate` schemas that use `uuid` columns.
5. The `RequestIdFilter` already uses v7 — client-supplied `X-Request-Id` is honored as-is; when absent/invalid, a v7 is
   generated and echoed back in the response header.
6. If you need a UUID v7 in a migration (defaults, seeds), use Postgres `gen_random_uuid()` fallback only as last resort
   (it's v4 — prefer inserting from app code).

**Absolute rules:**

1. All classes default to **package-private**. Add `public` ONLY when a class must be called from another module, and
   place it in the `api` sub-package.
2. `Entity`, `Repository`, `Service`, `Job`/`Scheduler`, `Listener`, `Jwt`/`Crypto` classes live in
   `internal/<concern>/` and are **NEVER** `public` unless absolutely unavoidable.
3. Other modules are **FORBIDDEN** from importing internal classes of another module. If you write
   `import com.gepe.app.auth.internal.repository.UserRepository;` from outside the `auth` module → **STOP, that's
   wrong.**
4. Modules may only know each other via:
    - Interface in `api` package (synchronous call), or
    - Event via `ApplicationEventPublisher` + `@ApplicationModuleListener` (async, decoupled).
5. REST controllers live in `internal/delivery/http/` (e.g. `auth/internal/delivery/http/AuthController.java`),
   package-private, and depend only on `internal/service/` + `internal/dto/`. A controller is **NEVER** placed in `api/`
   — `api/` is for other *modules* to call, not for HTTP entry points.

## API Versioning (MANDATORY)

1. **URI-path versioning is the ONLY allowed strategy.** Every public endpoint lives under `/api/<version>/...`
   (e.g. `/api/v1/auth/login`). Header, query-param, and media-type versioning are NOT used.
2. The current version is defined **once** in `platform/web/api/ApiVersions.java` (`ApiVersions.CURRENT = "v1"`).
   Controllers MUST build their `@RequestMapping` from this constant:
   ```java
   @RequestMapping("/api/" + ApiVersions.CURRENT + "/auth")
   ```
   NEVER hardcode `v1` (or any version literal) inside a controller's mapping.
3. A **breaking change** → add a NEW controller annotated `/api/v2/...` in the module's `internal/delivery/http/`,
   reusing the same internal services. Do NOT mutate the existing handler. Old versions stay live until deprecated.
4. `AuthSecurityConfig` matches `/api/**` (AuthSecurityConfig.java:30), which already covers every version — no
   security change is needed when adding a version.
5. Keep `auth.md`'s endpoint tables in sync whenever a route or version changes.

---

## 3. Database & Schema Rules

1. Each module has its own schema, with migration files at:
   ```
   src/main/resources/db/migration/V3__auth_signing_keys.sql
   src/main/resources/db/migration/V4__auth_users.sql
   ```
   (flat layout, see Flyway section above for naming convention).
2. Entity MUST declare schema explicitly:
   ```java
   @Entity
   @Table(name = "users", schema = "user")
   class User { }
   ```
3. **STRICTLY FORBIDDEN**: creating foreign keys across schemas (e.g. `notification.emails.user_id` REFERENCES
   `user.users.id`). Store the ID as a plain column, resolve via `api`/event.
4. **FORBIDDEN**: `@Entity` shared by 2 modules. If another module needs that data → create a DTO in `api`, don't share
   the entity.
5. Add migration files directly in the flat `db/migration/` folder. Each module's migrations use a `<module>_` prefix
   and the next global version number. Do NOT use per-module subdirectories.
6. **DTO over entity at boundaries**: `@Repository` methods return JPA entities, which are package-private in
   `internal/`. A service MUST NOT return an entity across its package boundary — it converts to a boundary-safe DTO (a
   record) and returns that. DTOs are required at: module `api`, cross-sub-package calls, async/event boundaries, and
   any method consumed by a controller. Pure same-package package-private helpers MAY pass the entity directly. A DTO
   MUST NOT import/annotate entity types; it must define boundary-safe enums (e.g. `SigningKeyStatus`) independent of
   the entity.
7. **Every listable table MUST be indexed for cursor pagination** (see §4). Any table that will be listed through a
   cursor-paginated endpoint MUST ship a composite index matching its canonical `ORDER BY`. The `id` column MUST be the
   last key of the index (deterministic tiebreaker). Examples:
   ```sql
   -- per-user list, newest first
   CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC, id DESC);
   -- global list, newest first
   CREATE INDEX idx_users_created ON users (created_at DESC, id DESC);
   ```
   An index that does NOT end with the PK `id` will NOT support stable keyset pagination — the query falls back to a
   sort/full scan and the whole point of §4 is lost.

---

## 4. Pagination (MANDATORY)

**Cursor/keyset pagination is the ONLY allowed pagination strategy.** OFFSET/LIMIT pagination is **FORBIDDEN**.

1. All list endpoints MUST paginate with an opaque cursor (base64-encoded `sort_value + id`) or an explicit keyset
   (`WHERE (sort_col, id) < (?, ?)`). Never `LIMIT n OFFSET m`.
2. `ORDER BY` MUST use a **stable, unique, indexed** key. The canonical pattern is:
   ```sql
   ORDER BY <sort_col> DESC, id DESC
   ```
   where `id` is the deterministic tiebreaker and the last key of every comparison. Composite indexes must match this
   exactly (see §3.7).
3. Each list response MUST include a `nextCursor` field (`null` when the last page is reached). The cursor MUST be
   opaque to clients — never expose raw column values.
4. Do NOT filter on non-indexed columns in the `WHERE` of a paginated query (reduces to a full scan per page).
5. **Why cursor, not offset** (security & scale):
   - Offset is O(n) scan — every page re-reads all previous rows; degrades badly as data grows.
   - Offset is **unstable** under concurrent inserts/deletes — rows can be skipped or duplicated between pages.
   - Cursor is deterministic, O(page size) per request, and gives a stable view while paging.
6. This rule synergizes with the **UUID v7** mandate: v7 IDs are time-ordered, so `ORDER BY ... , id DESC` already
   reflects insertion order without extra bookkeeping columns.

---

## 5. Inter-Module Communication

### Priority (most preferred first):

1. **Event** (`@ApplicationModuleListener`) — default for side-effects (send email, update report, etc.). No direct
   response needed.
2. **`api` interface** (Spring bean) — for synchronous needs, e.g. "check if this user exists" before proceeding.
3. **NEVER** access another module's repository/entity directly. Full stop.

### Event pattern (mandatory; do NOT use plain `@EventListener`):

```java
// module: user/api
public record UserRegistered(UUID userId, String email) {
}

// module: user (internal)
class UserService {
    void register(User user) {
        userRepository.save(user);
        events.publishEvent(new UserRegistered(user.getId(), user.getEmail()));
    }
}

// module: notification (internal)
@Component
class WelcomeEmailListener {
    @ApplicationModuleListener
        // mandatory — not @EventListener
    void on(UserRegistered event) {
        // must be idempotent! event may be retried on instance restart
    }
}
```

**Why `@ApplicationModuleListener` is mandatory**: events are persisted to the `EVENT_PUBLICATION` table (managed by
`spring-modulith-starter-jpa`), so if an instance dies mid-process, events are not lost and can be resumed. This is the
foundation for safe multi-instance operation.

**Listeners MUST be idempotent** — they can be retried, so never assume a listener runs exactly once.

---

## 6. Multi-Instance Rules

| Concern                         | Rule                                                                                                                                                                                                                                                                                                                                                                           |
|---------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Scheduled job (Quartz)          | MUST use clustering mode (`spring.quartz.job-store-type=jdbc`), so jobs don't duplicate across instances                                                                                                                                                                                                                                                                       |
| Event publication resubmission  | `spring.modulith.events.republish-outstanding-events-on-restart` is **disabled** (race-prone in multi-instance). Instead, a clustered Quartz job (`platform.modulith.EventPublicationResubmissionScheduler`) resubmits failed publications — safe across instances. The staleness monitor marks stuck PROCESSING/PUBLISHED/RESUBMITTED as FAILED so they become resubmittable. |
| Session/auth                    | Stateless (JWT/OAuth2 resource server). Never store state in local instance memory                                                                                                                                                                                                                                                                                             |
| Event listener                  | Must be idempotent — may be re-executed during retry                                                                                                                                                                                                                                                                                                                           |
| Local file upload (disk)        | FORBIDDEN. Use object storage (S3, etc.), not local disk                                                                                                                                                                                                                                                                                                                       |
| In-memory cache/singleton state | FORBIDDEN if state must be consistent across instances. Use Redis for shared cache                                                                                                                                                                                                                                                                                             |

---

## 7. Modularity Tests (NEVER delete or skip)

```java
package com.gepe.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    ApplicationModules modules = ApplicationModules.of(Application.class);

    @Test
    void verifyModularity() {
        modules.verify();   // fails automatically if boundaries are violated
    }

    @Test
    void writeDocumentationSnapshot() {
        new Documenter(modules).writeDocumentation();   // generates C4 diagrams to target/modulith-docs/
    }
}
```

If these tests are red → **do NOT bypass or comment them out.** Fix the architecture, not the test.

---

## 8. When to Create a New Module?

Create a new module when:

- It has its own bounded context (data + business rules with distinct responsibility).
- You can imagine it standing alone as a service if it needs to scale independently.

Don't create a new module when:

- It's just a bunch of generic helpers/utils (put those in `platform`, sparingly).
- Its function is still tightly coupled to another module (indicates no clear bounded context yet — merge until
  boundaries clarify).

---

## 9. Common Mistakes AI Must Avoid

1. ❌ Making every class `public` "so it's easy to call from anywhere" — this destroys the entire modulith.
2. ❌ Using `UUID.randomUUID()` (v4) or `UUID.nameUUIDFromBytes()` (v3) — ALL UUIDs must be v7 via `Uuidv7.generate()`.
3. ❌ Adding cross-schema FKs because "joins are easier."
4. ❌ Using plain `@EventListener` instead of `@ApplicationModuleListener`.
5. ❌ Placing new *module* migrations in subdirectories under `db/migration/`. All migrations must be flat in the root
   `db/migration/` folder with a `<module>_` prefix on the file name (e.g. `V3__auth_signing_keys.sql`).
   Flyway 12.x shares one global version namespace regardless of locations — subdirectories cause version
   conflicts.
6. ❌ Sharing one entity across 2 modules to "avoid duplicating fields."
7. ❌ Storing important state in local instance memory (e.g. `static Map` counter) assuming consistency across instances.
8. ❌ Putting entity/repository/service/jwt/crypto classes directly under the module root instead of
   `internal/<concern>/` — makes it impossible to tell public contract from implementation at a glance.
9. ❌ Merging Quartz `Job` (the work) and its `Trigger`/`JobDetail` registration (the schedule) into one class — split
   into `job/` and `*Scheduler` as shown in §2.
10. ❌ Creating a `publisher/` package/abstraction for events — publishing belongs inline in `service/`, where the
    transaction already lives.
11. ❌ Using OFFSET/LIMIT pagination anywhere — the ONLY allowed strategy is cursor/keyset (§4).
12. ❌ Hardcoding the API version in a controller's mapping (e.g. `@RequestMapping("/api/v1/auth")`) instead of
    `@RequestMapping("/api/" + ApiVersions.CURRENT + "/auth")`, or using header/query-param/media-type versioning
    instead of URI path versioning (§API Versioning).

---

## 10. Microservice Extraction Roadmap (when needed)

Because the structure is disciplined from the start, extraction is mechanical, not a rewrite:

1. Move the module's schema to a separate database.
2. Replace internal `api` interface calls (in-process) → REST/gRPC client.
3. Replace `@ApplicationModuleListener` → message broker consumer (Kafka/RabbitMQ). Event records are already
   serializable.
4. Deploy as a separate service, remove the old module from the monolith.

---

**Core principle:** _"When in doubt whether module A may access module B directly — the answer is NO. Always go through
`api` or event."_