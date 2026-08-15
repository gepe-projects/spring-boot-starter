package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.KeyManagementService;
import com.gepe.app.auth.api.dto.RotatedKeyDto;
import com.gepe.app.auth.api.dto.SigningKeyDto;
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
    public RotatedKeyDto rotateSigningKey() {
        return signingKeyRotationService.rotate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SigningKeyDto> listSigningKeys() {
        return signingKeyService.getAll().stream()
                .map(this::toInfo)
                .toList();
    }

    private SigningKeyDto toInfo(SigningKeyData key) {
        return new SigningKeyDto(
                key.kid(),
                key.algorithm(),
                key.status(),
                key.notBefore(),
                key.notAfter(),
                key.createdAt());
    }
}
