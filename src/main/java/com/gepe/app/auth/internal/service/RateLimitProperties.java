package com.gepe.app.auth.internal.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.rate-limit")
public record RateLimitProperties(Duration baseDelay, Duration maxDelay, int maxAttempts) {
}
