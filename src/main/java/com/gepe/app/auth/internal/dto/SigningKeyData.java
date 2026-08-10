package com.gepe.app.auth.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record SigningKeyData(
        UUID kid,
        String publicKey,
        byte[] privateKeyCipher,
        String encKeyId,
        SigningKeyStatus status,
        Instant notBefore,
        Instant notAfter) {
}
