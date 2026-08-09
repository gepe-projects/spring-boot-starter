package com.gepe.app.auth.api;

import java.util.UUID;

/** Dipublish saat user BARU pertama kali dibuat (login pertama, creds atau google). */
public record UserRegistered(UUID userId, String email) {}
