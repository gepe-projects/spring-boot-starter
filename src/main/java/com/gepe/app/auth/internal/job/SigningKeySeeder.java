package com.gepe.app.auth.internal.job;

import com.gepe.app.auth.internal.crypto.MasterKeyProvider;
import com.gepe.app.auth.internal.crypto.RsaKeyService;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.auth.internal.repository.SigningKeyRepository;
import com.gepe.app.platform.support.Uuidv7;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class SigningKeySeeder implements ApplicationRunner {

    private final SigningKeyRepository signingKeyRepository;
    private final RsaKeyService rsaKeyService;
    private final MasterKeyProvider masterKeyProvider;

    @Override
    public void run(ApplicationArguments args) {
        if (signingKeyRepository.findByStatusIn(
                List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS))
                .isEmpty()) {
            log.info("No active signing keys found — generating initial key pair...");

            KeyPair keyPair = rsaKeyService.generateKeyPair();
            RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey priv = (RSAPrivateKey) keyPair.getPrivate();

            SigningKey key = new SigningKey(
                    Uuidv7.generate(),
                    rsaKeyService.publicKeyToBase64(pub),
                    rsaKeyService.encryptPrivateKey(priv),
                    masterKeyProvider.getCurrentKeyId(),
                    SigningKey.Status.ACTIVE,
                    Instant.now(),
                    null
            );

            signingKeyRepository.save(key);
            log.info("Initial signing key created: kid={}", key.getKid());
        }
    }
}
