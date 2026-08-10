package com.gepe.app.auth.internal.oauth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthProperties.class)
class OAuthConfig {

    @Bean
    JwtDecoder googleIdTokenDecoder() {
        return NimbusJwtDecoder.withIssuerLocation("https://accounts.google.com")
                .build();
    }
}
