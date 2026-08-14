package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.KeyManagementService;
import com.gepe.app.auth.api.RotatedKeyResponse;
import com.gepe.app.auth.api.SigningKeyInfo;
import com.gepe.app.auth.internal.dto.SigningKeyData;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementasi kontrak manajemen kunci ({@link KeyManagementService}) — membungkus
 * {@link SigningKeyRotationService} (rotasi) dan {@link SigningKeyService} (listing).
 * Rotasi signing key berjalan runtime (tanpa deploy ulang); master key TIDAK bagian ini.
 */
@Service
@RequiredArgsConstructor
public class KeyManagementServiceImpl implements KeyManagementService {

    private final SigningKeyRotationService signingKeyRotationService;
    private final SigningKeyService signingKeyService;

    @Override
    @Transactional
    public RotatedKeyResponse rotateSigningKey() {
        return signingKeyRotationService.rotate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SigningKeyInfo> listSigningKeys() {
        return signingKeyService.getAll().stream()
                .map(this::toInfo)
                .toList();
    }

    private SigningKeyInfo toInfo(SigningKeyData key) {
        return new SigningKeyInfo(
                key.kid(),
                key.algorithm(),
                key.status().name(),
                key.notBefore(),
                key.notAfter(),
                key.createdAt());
    }
}
