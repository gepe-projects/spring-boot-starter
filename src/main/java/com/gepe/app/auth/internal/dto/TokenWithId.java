package com.gepe.app.auth.internal.dto;

import java.util.UUID;

public record TokenWithId(UUID id, String raw) {}
