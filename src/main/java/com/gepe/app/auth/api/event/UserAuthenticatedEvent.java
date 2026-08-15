package com.gepe.app.auth.api.event;

import java.util.UUID;

/** Dipublish setelah login sukses (AGENTS.md §5 — async side-effect). */
public record UserAuthenticatedEvent(UUID userId, String email) {}
