package com.gepe.app.auth.api;

import com.gepe.app.auth.api.dto.UserDto;
import java.util.Optional;
import java.util.UUID;

/**
 * Synchronous contract untuk modul lain (prioritas 2 di AGENTS.md §5).
 */
public interface UserService {

    Optional<UserDto> findByUserId(UUID userId);

    Optional<UserDto> findByEmail(String email);

    boolean existsByEmail(String email);
}
