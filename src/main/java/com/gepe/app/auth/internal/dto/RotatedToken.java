package com.gepe.app.auth.internal.dto;

import java.util.UUID;

public record RotatedToken(UUID id, String raw, UUID userId, UUID sessionId) {}
