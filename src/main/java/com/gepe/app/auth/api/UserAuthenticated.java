package com.gepe.app.auth.api;

import java.util.UUID;

/** Dipublish setelah login sukses (AGENTS.md §5 — async side-effect). */
public record UserAuthenticated(UUID userId, String email) {}
