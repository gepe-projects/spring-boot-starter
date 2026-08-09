package com.gepe.app.auth.internal.oauth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.oauth")
record OAuthProperties(String redirectUri, List<String> frontendRedirectUris, Duration oneTimeCodeTtl) {
}
