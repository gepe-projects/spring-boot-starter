package com.gepe.app.auth.internal.jwt;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
public record JwtProperties(
        @NotBlank
        @DefaultValue("http://localhost:8080")
        String issuer,

        @DefaultValue("15m")
        Duration accessTokenTtl,

        @DefaultValue("30d")
        Duration refreshTokenTtl,

        @NotBlank
        @DefaultValue("0 0 3 1 * ?")
        String signingKeyRotationCron,

        @NotBlank
        @DefaultValue("0 0 4 1 * ?")
        String masterKeyRotationCron
) {}
