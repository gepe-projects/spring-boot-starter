package com.gepe.app.auth.api;

import java.util.Optional;
import java.util.UUID;

/** Synchronous contract untuk modul lain (prioritas 2 di AGENTS.md §5). */
public interface AuthApi {

    Optional<CurrentUser> findByUserId(UUID userId);

    Optional<CurrentUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
