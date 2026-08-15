package com.gepe.app.auth.api.dto;

/**
 * Status lifecycle signing key — boundary enum public (dipakai {@code SigningKeyDto},
 * {@code RotatedKeyDto}, dan DTO internal {@code SigningKeyData}). Independen dari
 * entity internal {@code auth.internal.entity.SigningKey.Status} (AGENTS.md §3.6);
 * invariant "1 ACTIVE" dijaga di database.
 */
public enum SigningKeyStatus {
    /** Key aktif — dipakai menandatangani JWT baru. */
    ACTIVE,
    /** Key transisi (overlap window 1 jam) — masih bisa memverifikasi JWT lama. */
    PREVIOUS,
    /** Key kedaluwarsa — tidak dipakai lagi. */
    RETIRED
}
