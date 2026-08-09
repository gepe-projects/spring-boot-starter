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

- Platform/infra migrations live at the default `classpath:db/migration/` (flat):
    - `V1__event_publication.sql` — modulith event publication registry table.
    - `V2__quartz_tables.sql` — Quartz clustered scheduler tables.
- Per-module schemas use subdirectories. When module schemas are added, list each path explicitly:
  ```yaml
  spring:
    flyway:
      locations:
        - classpath:db/migration
        - classpath:db/migration/user
        - classpath:db/migration/notification
  ```
  Flyway does not recurse into subdirectories automatically.

---

## Spring Security

- `spring-boot-starter-security` is on the classpath with **no custom `SecurityFilterChain` bean**. All endpoints are
  secured by default (HTTP Basic, generated password) until a security config is added.
- `spring-boot-starter-security-oauth2-client` is present but has no provider/client registration configured.

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

---

## 3. Database & Schema Rules

1. Each module has its own schema, with separate migration files at:
   ```
   src/main/resources/db/migration/<module-name>/V1__init.sql
   ```
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
5. Add each new module path to `flyway.locations` (comma-separated or YAML list). Don't dump all migrations in one flat
   folder.
6. **DTO over entity at boundaries**: `@Repository` methods return JPA entities, which are package-private in
   `internal/`. A service MUST NOT return an entity across its package boundary — it converts to a boundary-safe DTO (a
   record) and returns that. DTOs are required at: module `api`, cross-sub-package calls, async/event boundaries, and
   any method consumed by a controller. Pure same-package package-private helpers MAY pass the entity directly. A DTO
   MUST NOT import/annotate entity types; it must define boundary-safe enums (e.g. `SigningKeyStatus`) independent of
   the entity.

---

## 4. Inter-Module Communication

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

## 5. Multi-Instance Rules

| Concern                         | Rule                                                                                                                                                                                                                                                                                                                                                                           |
|---------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Scheduled job (Quartz)          | MUST use clustering mode (`spring.quartz.job-store-type=jdbc`), so jobs don't duplicate across instances                                                                                                                                                                                                                                                                       |
| Event publication resubmission  | `spring.modulith.events.republish-outstanding-events-on-restart` is **disabled** (race-prone in multi-instance). Instead, a clustered Quartz job (`platform.modulith.EventPublicationResubmissionScheduler`) resubmits failed publications — safe across instances. The staleness monitor marks stuck PROCESSING/PUBLISHED/RESUBMITTED as FAILED so they become resubmittable. |
| Session/auth                    | Stateless (JWT/OAuth2 resource server). Never store state in local instance memory                                                                                                                                                                                                                                                                                             |
| Event listener                  | Must be idempotent — may be re-executed during retry                                                                                                                                                                                                                                                                                                                           |
| Local file upload (disk)        | FORBIDDEN. Use object storage (S3, etc.), not local disk                                                                                                                                                                                                                                                                                                                       |
| In-memory cache/singleton state | FORBIDDEN if state must be consistent across instances. Use Redis for shared cache                                                                                                                                                                                                                                                                                             |

---

## 6. Modularity Tests (NEVER delete or skip)

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

## 7. When to Create a New Module?

Create a new module when:

- It has its own bounded context (data + business rules with distinct responsibility).
- You can imagine it standing alone as a service if it needs to scale independently.

Don't create a new module when:

- It's just a bunch of generic helpers/utils (put those in `platform`, sparingly).
- Its function is still tightly coupled to another module (indicates no clear bounded context yet — merge until
  boundaries clarify).

---

## 8. Common Mistakes AI Must Avoid

1. ❌ Making every class `public` "so it's easy to call from anywhere" — this destroys the entire modulith.
2. ❌ Using `UUID.randomUUID()` (v4) or `UUID.nameUUIDFromBytes()` (v3) — ALL UUIDs must be v7 via `Uuidv7.generate()`.
3. ❌ Adding cross-schema FKs because "joins are easier."
4. ❌ Using plain `@EventListener` instead of `@ApplicationModuleListener`.
5. ❌ Placing new *module* migrations directly in `db/migration` root without per-module subdirectories. (Exception:
   infra migrations like `event_publication` and `QRTZ_*` tables live flat in `db/migration` — they're platform, not
   module-owned.)
6. ❌ Sharing one entity across 2 modules to "avoid duplicating fields."
7. ❌ Storing important state in local instance memory (e.g. `static Map` counter) assuming consistency across instances.
8. ❌ Putting entity/repository/service/jwt/crypto classes directly under the module root instead of
   `internal/<concern>/` — makes it impossible to tell public contract from implementation at a glance.
9. ❌ Merging Quartz `Job` (the work) and its `Trigger`/`JobDetail` registration (the schedule) into one class — split
   into `job/` and `*Scheduler` as shown in §2.
10. ❌ Creating a `publisher/` package/abstraction for events — publishing belongs inline in `service/`, where the
    transaction already lives.

---

## 9. Microservice Extraction Roadmap (when needed)

Because the structure is disciplined from the start, extraction is mechanical, not a rewrite:

1. Move the module's schema to a separate database.
2. Replace internal `api` interface calls (in-process) → REST/gRPC client.
3. Replace `@ApplicationModuleListener` → message broker consumer (Kafka/RabbitMQ). Event records are already
   serializable.
4. Deploy as a separate service, remove the old module from the monolith.

---

**Core principle:** _"When in doubt whether module A may access module B directly — the answer is NO. Always go through
`api` or event."_