package com.gepe.app.auth.internal.job;

import com.gepe.app.auth.internal.crypto.AesGcmService;
import com.gepe.app.auth.internal.crypto.MasterKeyProvider;
import com.gepe.app.auth.internal.entity.SigningKey;
import com.gepe.app.auth.internal.repository.SigningKeyRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

@Slf4j
@DisallowConcurrentExecution
@RequiredArgsConstructor
class MasterKeyRotationJob extends QuartzJobBean {

    private final SigningKeyRepository signingKeyRepository;
    private final MasterKeyProvider masterKeyProvider;
    private final AesGcmService aesGcmService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        if (!masterKeyProvider.hasPrevious()) {
            log.info("No previous master key configured — skipping master key rotation");
            return;
        }

        Instant now = Instant.now();
        List<SigningKey> keys = signingKeyRepository.findByStatusInAndNotAfterAfter(
                List.of(SigningKey.Status.ACTIVE, SigningKey.Status.PREVIOUS), now);

        String newKeyId = masterKeyProvider.getCurrentKeyId();
        String oldKeyId = masterKeyProvider.getPreviousKeyId();

        for (SigningKey key : keys) {
            if (!key.getEncKeyId().equals(oldKeyId)) {
                continue;
            }
            try {
                byte[] pkcs8 = aesGcmService.decrypt(key.getPrivateKeyCipher(), oldKeyId);
                byte[] newCipher = aesGcmService.encrypt(pkcs8);
                key.setPrivateKeyCipher(newCipher);
                key.setEncKeyId(newKeyId);
                signingKeyRepository.save(key);

                log.info("Re-encrypted signing key: kid={}, old_enc_key={} -> new_enc_key={}",
                         key.getKid(), oldKeyId, newKeyId);
            } catch (Exception e) {
                log.error("Failed to re-encrypt signing key: kid={}", key.getKid(), e);
            }
        }

        log.info("Master key rotation completed for {} keys", keys.size());
    }
}
