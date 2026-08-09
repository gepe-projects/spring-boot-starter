package com.gepe.app.auth.internal.jwt;

import static com.gepe.app.auth.internal.jwt.JwtClaims.CLAIM_EMAIL;
import static com.gepe.app.auth.internal.jwt.JwtClaims.CLAIM_ROLES;

import com.gepe.app.auth.internal.crypto.RsaKeyService;
import com.gepe.app.auth.internal.dto.SigningKeyData;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.service.SigningKeyService;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.support.Uuidv7;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtService {

    private final SigningKeyService signingKeyService;
    private final RsaKeyService rsaKeyService;
    private final JwtProperties properties;

    public SignedJWT issueAccessToken(UUID userId, String email, List<String> roles) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        if (email.isBlank()) throw new ServiceException(AuthError.TOKEN_INVALID_CLAIM);
        List<String> safeRoles = roles != null ? List.copyOf(roles) : List.of();
        return issue(userId, email, safeRoles, properties.accessTokenTtl());
    }

    private SignedJWT issue(UUID userId, String email, List<String> roles, Duration ttl) {
        SigningKeyData active = signingKeyService.getActive()
                .orElseThrow(() -> new ServiceException(AuthError.KEY_NOT_FOUND));

        Instant now = Instant.now();
        UUID jti = Uuidv7.generate();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .jwtID(jti.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, roles)
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(active.kid().toString())
                .build();

        try {
            RSAPrivateKey privateKey = rsaKeyService.decryptPrivateKey(active);
            SignedJWT signedJwt = new SignedJWT(header, claims);
            signedJwt.sign(new RSASSASigner(privateKey));
            return signedJwt;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(AuthError.KEY_GENERATION_FAILED);
        }
    }
}
