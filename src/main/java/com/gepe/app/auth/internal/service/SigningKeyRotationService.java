package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.RotatedKeyResponse;
import com.gepe.app.auth.internal.crypto.MasterKeyProvider;
import com.gepe.app.auth.internal.crypto.RsaKeyService;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.auth.internal.repository.SigningKeyRepository;
import com.gepe.app.platform.support.Uuidv7;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SigningKeyRotationService {

    private static final Duration OVERLAP_WINDOW = Duration.ofHours(1);
    private static final String JWKS_CACHE_KEY = "auth:jwks";

    private final SigningKeyRepository signingKeyRepository;
    private final RsaKeyService rsaKeyService;
    private final MasterKeyProvider masterKeyProvider;
    private final StringRedisTemplate stringRedisTemplate;

    public RotatedKeyResponse rotate() {
        Instant now = Instant.now();

        List<SigningKey> previousKeys = signingKeyRepository.findByStatusIn(
                List.of(SigningKey.Status.PREVIOUS));
        for (SigningKey key : previousKeys) {
            if (key.getNotAfter() != null && key.getNotAfter().isBefore(now)) {
                key.setStatus(SigningKey.Status.RETIRED);
                signingKeyRepository.save(key);
                log.info("Retired signing key: kid={}", key.getKid());
            }
        }

        signingKeyRepository.findActiveForUpdate(SigningKey.Status.ACTIVE)
                .ifPresent(active -> {
                    active.setStatus(SigningKey.Status.PREVIOUS);
                    active.setNotAfter(now.plus(OVERLAP_WINDOW));
                    signingKeyRepository.saveAndFlush(active);
                    log.info("Transitioned signing key to PREVIOUS: kid={}, not_after={}",
                             active.getKid(), active.getNotAfter());
                });

        KeyPair keyPair = rsaKeyService.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey priv = (RSAPrivateKey) keyPair.getPrivate();

        String pubBase64 = rsaKeyService.publicKeyToBase64(pub);
        byte[] privCipher = rsaKeyService.encryptPrivateKey(priv);

        SigningKey newKey = new SigningKey(
                Uuidv7.generate(),
                pubBase64,
                privCipher,
                masterKeyProvider.getCurrentKeyId(),
                SigningKey.Status.ACTIVE,
                now,
                null
        );

        signingKeyRepository.save(newKey);
        log.info("Generated new ACTIVE signing key: kid={}", newKey.getKid());

        stringRedisTemplate.delete(JWKS_CACHE_KEY);

        return new RotatedKeyResponse(newKey.getKid(), "ACTIVE", newKey.getNotBefore());
    }
}
