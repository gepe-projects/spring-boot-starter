package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.dto.SigningKeyData;
import com.gepe.app.auth.internal.dto.SigningKeyStatus;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.auth.internal.repository.SigningKeyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SigningKeyService {

    private final SigningKeyRepository signingKeyRepository;

    public Optional<SigningKeyData> getActive() {
        return signingKeyRepository
                .findFirstByStatusOrderByNotBeforeDesc(SigningKey.Status.ACTIVE)
                .map(this::toData);
    }

    public List<SigningKeyData> getActiveOrPrevious() {
        return signingKeyRepository
                .findByStatusIn(List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS))
                .stream()
                .map(this::toData)
                .toList();
    }

    public List<SigningKeyData> getActiveOrPreviousNotExpired(Instant now) {
        return signingKeyRepository
                .findByStatusInAndNotAfterAfter(
                        List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS), now)
                .stream()
                .map(this::toData)
                .toList();
    }

    /** Semua key (termasuk RETIRED), terbaru dulu — untuk listing admin. */
    public List<SigningKeyData> getAll() {
        return signingKeyRepository.findAllByOrderByNotBeforeDesc()
                .stream()
                .map(this::toData)
                .toList();
    }

    private SigningKeyData toData(SigningKey e) {
        return new SigningKeyData(
                e.getKid(),
                e.getPublicKey(),
                e.getPrivateKeyCipher(),
                e.getEncKeyId(),
                e.getAlgorithm(),
                mapStatus(e.getStatus()),
                e.getNotBefore(),
                e.getNotAfter(),
                e.getCreatedAt());
    }

    private static SigningKeyStatus mapStatus(SigningKey.Status s) {
        return switch (s) {
            case ACTIVE -> SigningKeyStatus.ACTIVE;
            case PREVIOUS -> SigningKeyStatus.PREVIOUS;
            case RETIRED -> SigningKeyStatus.RETIRED;
        };
    }
}
