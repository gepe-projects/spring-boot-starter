package com.gepe.app.auth.api;

import com.gepe.app.auth.api.dto.RotatedKeyDto;
import com.gepe.app.auth.api.dto.SigningKeyDto;
import java.util.List;

/**
 * Kontrak manajemen kunci penandatanganan (RSA signing key) untuk module {@code admin}.
 *
 * <p>Rotasi signing key bisa dilakukan runtime (tanpa deploy ulang) — key baru dibuat,
 * key lama transisi ACTIVE → PREVIOUS → RETIRED, invariant "1 ACTIVE" dijaga database.
 * Master key tetap env-based (bukan bagian kontrak ini).
 */
public interface KeyManagementService {

    RotatedKeyDto rotateSigningKey();

    /** Daftar semua signing key (ACTIVE/PREVIOUS/RETIRED), terbaru dulu. */
    List<SigningKeyDto> listSigningKeys();
}
