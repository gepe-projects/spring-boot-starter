package com.gepe.app.auth.internal.delivery.http;

import com.gepe.app.auth.internal.dto.SigningKeyData;
import com.gepe.app.auth.internal.service.SigningKeyService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class JwksController {

    private static final String JWKS_CACHE_KEY = "auth:jwks";

    private final SigningKeyService signingKeyService;
    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> jwks() {
        String cached = stringRedisTemplate.opsForValue().get(JWKS_CACHE_KEY);
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }

        List<SigningKeyData> keys = signingKeyService.getActiveOrPrevious();
        List<JWK> jwks = keys.stream()
                .map(this::toJwk)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        String json = new JWKSet(jwks).toPublicJWKSet().toString();
        stringRedisTemplate.opsForValue().set(JWKS_CACHE_KEY, json);

        return ResponseEntity.ok(json);
    }

    private JWK toJwk(SigningKeyData key) {
        try {
            byte[] pubBytes = Base64.getDecoder().decode(key.publicKey());
            X509EncodedKeySpec spec = new X509EncodedKeySpec(pubBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(spec);

            return new RSAKey.Builder(pub)
                    .keyID(key.kid().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }
}
