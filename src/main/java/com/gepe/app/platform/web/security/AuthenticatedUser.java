package com.gepe.app.platform.web.security;

import java.util.List;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, String email, List<String> roles) {}
