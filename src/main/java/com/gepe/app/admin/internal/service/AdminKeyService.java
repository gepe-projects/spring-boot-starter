package com.gepe.app.admin.internal.service;

import com.gepe.app.auth.api.KeyManagementService;
import com.gepe.app.auth.api.RotatedKeyResponse;
import com.gepe.app.auth.api.SigningKeyInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case admin untuk manajemen kunci penandatanganan. Rotasi signing key runtime
 * (tanpa deploy ulang); hasilnya diaudit di transaksi yang sama.
 */
@Service
@RequiredArgsConstructor
public class AdminKeyService {

    private final KeyManagementService keyManagementService;
    private final AdminAuditService auditService;

    @Transactional
    public RotatedKeyResponse rotateSigningKey(UUID actorId) {
        RotatedKeyResponse result = keyManagementService.rotateSigningKey();
        auditService.record(actorId, "SIGNING_KEY_ROTATED", "SIGNING_KEY", result.kid().toString(),
                Map.of("status", result.status(), "notBefore", result.notBefore()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<SigningKeyInfo> listSigningKeys() {
        return keyManagementService.listSigningKeys();
    }
}
