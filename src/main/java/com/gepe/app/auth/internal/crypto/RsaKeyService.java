package com.gepe.app.auth.internal.crypto;

import com.gepe.app.auth.internal.dto.SigningKeyData;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.platform.exception.ServiceException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RsaKeyService {

    private static final int RSA_KEY_SIZE = 2048;

    private final AesGcmService aesGcmService;

    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(RSA_KEY_SIZE, new SecureRandom());
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new ServiceException(AuthError.KEY_GENERATION_FAILED);
        }
    }

    public String publicKeyToBase64(RSAPublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public byte[] encryptPrivateKey(RSAPrivateKey privateKey) {
        return aesGcmService.encrypt(privateKey.getEncoded());
    }

    public RSAPrivateKey decryptPrivateKey(SigningKeyData signingKey) {
        byte[] pkcs8 = aesGcmService.decrypt(
                signingKey.privateKeyCipher(),
                signingKey.encKeyId());
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new ServiceException(AuthError.DECRYPTION_FAILED);
        }
    }

    public RSAPublicKey parsePublicKey(String base64PublicKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new ServiceException(AuthError.KEY_NOT_FOUND);
        }
    }
}
