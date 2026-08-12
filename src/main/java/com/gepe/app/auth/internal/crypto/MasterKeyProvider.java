package com.gepe.app.auth.internal.crypto;

import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.platform.exception.ServiceException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MasterKeyProvider {

    private static final String KEY_ID_CURRENT = "current";
    private static final String KEY_ID_PREVIOUS = "previous";
    private static final int AES_KEY_SIZE = 32; // AES-256

    private final Map<String, SecretKey> keys = new ConcurrentHashMap<>();

    // Property `master.key.current` relax-binds ke env var MASTER_KEY_CURRENT (default: kosong).
    // Default hanya fallback (mis. profil test); di produksi env var tetap sumber utama.
    MasterKeyProvider(@Value("${master.key.current:}") String current,
                      @Value("${master.key.previous:}") String previous) {
        loadKey(current, KEY_ID_CURRENT);
        loadKey(previous, KEY_ID_PREVIOUS);

        if (keys.isEmpty()) {
            throw new ServiceException(AuthError.MASTER_KEY_INVALID);
        }
    }

    public SecretKey getCurrent() {
        SecretKey key = keys.get(KEY_ID_CURRENT);
        if (key == null) {
            throw new ServiceException(AuthError.MASTER_KEY_INVALID);
        }
        return key;
    }

    public SecretKey getById(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new ServiceException(AuthError.MASTER_KEY_INVALID);
        }
        return key;
    }

    public boolean hasPrevious() {
        return keys.containsKey(KEY_ID_PREVIOUS);
    }

    public String getCurrentKeyId() {
        return KEY_ID_CURRENT;
    }

    public String getPreviousKeyId() {
        return KEY_ID_PREVIOUS;
    }

    private void loadKey(String encoded, String keyId) {
        if (encoded == null || encoded.isBlank()) {
            log.warn("Master key {} is not set", keyId);
            return;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            if (raw.length != AES_KEY_SIZE) {
                log.error("Master key {} must be 32 bytes (AES-256), got {} bytes",
                          keyId, raw.length);
                return;
            }
            keys.put(keyId, new SecretKeySpec(raw, "AES"));
            log.info("Loaded master key: {}", keyId);
        } catch (IllegalArgumentException e) {
            log.error("Master key {} is not valid Base64", keyId, e);
        }
    }
}
