package com.gepe.app.auth.api;

import java.util.List;
import java.util.UUID;

public record UserDto(UUID userId, String email, boolean emailVerified, List<String> roles) {}
