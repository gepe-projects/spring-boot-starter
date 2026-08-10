package com.gepe.app.auth.api;

import java.util.UUID;

public record CurrentUser(UUID userId, String email, boolean emailVerified) {}
