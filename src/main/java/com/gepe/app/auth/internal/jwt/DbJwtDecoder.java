package com.gepe.app.auth.internal.jwt;

import com.gepe.app.auth.internal.dto.SigningKeyData;
import com.gepe.app.auth.internal.service.SigningKeyService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class DbJwtDecoder implements JwtDecoder {

    private final SigningKeyService signingKeyService;

    DbJwtDecoder(SigningKeyService signingKeyService) {
        this.signingKeyService = signingKeyService;
    }

    @Override
    public Jwt decode(String token) {
        List<SigningKeyData> activeKeys = signingKeyService.getActiveOrPrevious();
        if (activeKeys.isEmpty()) {
            throw new JwtException("No active signing keys available");
        }

        JWKSource<SecurityContext> jwkSource = (jwkSelector, context) -> {
            List<JWK> jwks = activeKeys.stream()
                    .map(this::toJwk)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            return jwkSelector.select(new JWKSet(jwks));
        };

        NimbusJwtDecoder delegate = NimbusJwtDecoder.withJwkSource(jwkSource)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        return delegate.decode(token);
    }

    private JWK toJwk(SigningKeyData key) {
        try {
            byte[] pubBytes = Base64.getDecoder().decode(key.publicKey());
            X509EncodedKeySpec spec = new X509EncodedKeySpec(pubBytes);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(spec);

            return new RSAKey.Builder(pub)
                    .keyID(key.kid().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            log.error("Failed to build JWK for kid={}", key.kid(), e);
            return null;
        }
    }
}
