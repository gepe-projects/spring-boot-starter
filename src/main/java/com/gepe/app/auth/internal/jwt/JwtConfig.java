package com.gepe.app.auth.internal.jwt;

import com.gepe.app.auth.internal.service.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtProperties.class, RateLimitProperties.class})
class JwtConfig {
}
