package com.gepe.app.auth.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Hasil rotasi signing key — boundary record public (module lain, mis. admin,
 * menerimanya via {@link KeyManagementService}). Dipindah dari
 * {@code auth.internal.dto} karena diekspos keluar module.
 */
public record RotatedKeyResponse(
        UUID kid,
        String status,
        Instant notBefore) {
}
