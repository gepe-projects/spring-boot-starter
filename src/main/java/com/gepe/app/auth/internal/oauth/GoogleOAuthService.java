package com.gepe.app.auth.internal.oauth;

import com.gepe.app.auth.internal.dto.TokenResponse;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.service.AuthService;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.support.Uuidv7;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class GoogleOAuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int PKCE_VERIFIER_BYTES = 64;
    private static final int OTC_BYTES = 32;
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String STATE_PREFIX = "oauth:state:";
    private static final String OTC_PREFIX = "oauth:otc:";

    private final AuthService authService;
    private final OAuthProperties oAuthProperties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final JwtDecoder googleIdTokenDecoder;
    private final ClientRegistration googleRegistration;

    GoogleOAuthService(
            AuthService authService,
            OAuthProperties oAuthProperties,
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("googleIdTokenDecoder") JwtDecoder googleIdTokenDecoder,
            ClientRegistrationRepository clientRegistrationRepository) {
        this.authService = authService;
        this.oAuthProperties = oAuthProperties;
        this.redis = stringRedisTemplate;
        this.googleIdTokenDecoder = googleIdTokenDecoder;
        this.googleRegistration = clientRegistrationRepository.findByRegistrationId("google");
    }

    public String begin(String redirectUrl, String deviceInfo, String ipAddress) {
        if (!isValidRedirect(redirectUrl)) {
            throw new ServiceException(AuthError.OAUTH_REDIRECT_FORBIDDEN);
        }

        String state = Uuidv7.generate().toString();
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = computeCodeChallenge(codeVerifier);
        String nonce = Uuidv7.generate().toString();

        OAuthState stateData = new OAuthState(codeVerifier, nonce, redirectUrl, deviceInfo, ipAddress);
        try {
            redis.opsForValue().set(STATE_PREFIX + state, objectMapper.writeValueAsString(stateData), STATE_TTL);
        } catch (JacksonException e) {
            throw new ServiceException(AuthError.OAUTH_PROVIDER_ERROR);
        }

        String authorizationUri = googleRegistration.getProviderDetails().getAuthorizationUri();
        String authUrl = UriComponentsBuilder.fromUriString(authorizationUri)
                .queryParam("response_type", "code")
                .queryParam("client_id", googleRegistration.getClientId())
                .queryParam("redirect_uri", oAuthProperties.redirectUri())
                .queryParam("scope", String.join(" ", googleRegistration.getScopes()))
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("nonce", nonce)
                .queryParam("access_type", "online")
                .toUriString();

        return authUrl;
    }

    public URI callback(String stateParam, String code) {
        String raw = redis.opsForValue().getAndDelete(STATE_PREFIX + stateParam);
        if (raw == null) {
            throw new ServiceException(AuthError.OAUTH_STATE_INVALID);
        }

        OAuthState stateData;
        try {
            stateData = objectMapper.readValue(raw, OAuthState.class);
        } catch (JacksonException e) {
            throw new ServiceException(AuthError.OAUTH_STATE_INVALID);
        }

        String idToken = exchangeCodeForIdToken(code, stateData.codeVerifier());

        Jwt jwt = googleIdTokenDecoder.decode(idToken);

        if (jwt.getAudience() == null || !jwt.getAudience().contains(googleRegistration.getClientId())) {
            log.warn("Google id_token audience mismatch: expected {}", googleRegistration.getClientId());
            throw new ServiceException(AuthError.OAUTH_PROVIDER_ERROR);
        }

        if (!Boolean.TRUE.equals(jwt.getClaim("email_verified"))) {
            throw new ServiceException(AuthError.OAUTH_PROVIDER_ERROR);
        }

        String jwtNonce = jwt.getClaimAsString("nonce");
        if (!stateData.nonce().equals(jwtNonce)) {
            log.warn("Google id_token nonce mismatch");
            throw new ServiceException(AuthError.OAUTH_PROVIDER_ERROR);
        }

        String sub = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        String picture = jwt.getClaimAsString("picture");

        TokenResponse tokens = authService.googleLogin(
                sub, email, name, picture, stateData.deviceInfo(), stateData.ipAddress());

        String oneTimeCode = generateOneTimeCode();
        String otcData;
        try {
            otcData = objectMapper.writeValueAsString(tokens);
        } catch (JacksonException e) {
            throw new ServiceException(AuthError.OAUTH_PROVIDER_ERROR);
        }
        redis.opsForValue().set(OTC_PREFIX + oneTimeCode, otcData, oAuthProperties.oneTimeCodeTtl());

        return UriComponentsBuilder.fromUriString(stateData.redirectUrl())
                .queryParam("code", oneTimeCode)
                .build(Map.of());
    }

    public TokenResponse exchange(String oneTimeCode) {
        String raw = redis.opsForValue().getAndDelete(OTC_PREFIX + oneTimeCode);
        if (raw == null) {
            throw new ServiceException(AuthError.OAUTH_CODE_REUSED);
        }
        try {
            return objectMapper.readValue(raw, TokenResponse.class);
        } catch (JacksonException e) {
            throw new ServiceException(AuthError.OAUTH_CODE_REUSED);
        }
    }

    public URI errorRedirect(String stateParam, String error) {
        String raw = redis.opsForValue().getAndDelete(STATE_PREFIX + stateParam);
        if (raw != null) {
            try {
                OAuthState stateData = objectMapper.readValue(raw, OAuthState.class);
                return UriComponentsBuilder.fromUriString(stateData.redirectUrl())
                        .queryParam("error", error)
                        .build(Map.of());
            } catch (JacksonException e) {
                log.warn("Failed to parse OAuth state during error redirect", e);
            }
        }

        String fallbackOrigin = oAuthProperties.frontendRedirectUris().stream()
                .map(this::extractOrigin)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("http://localhost:3000");
        return UriComponentsBuilder.fromUriString(fallbackOrigin + "/sign-in")
                .queryParam("error", error)
                .build(Map.of());
    }

    private String exchangeCodeForIdToken(String code, String codeVerifier) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("redirect_uri", oAuthProperties.redirectUri());
        params.add("client_id", googleRegistration.getClientId());
        params.add("client_secret", googleRegistration.getClientSecret());
        params.add("code_verifier", codeVerifier);

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String tokenUri = googleRegistration.getProviderDetails().getTokenUri();
        try {
            var response = restTemplate.exchange(
                    tokenUri, HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    Map.class);

            Map<?, ?> body = response.getBody();
            if (body == null || !body.containsKey("id_token")) {
                log.error("Google token response missing id_token: {}", body);
                throw new ServiceException(AuthError.OAUTH_PROVIDER_ERROR);
            }
            return (String) body.get("id_token");
        } catch (ServiceException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Failed to exchange authorization code with Google", e);
            throw new ServiceException(AuthError.OAUTH_PROVIDER_ERROR);
        }
    }

    private boolean isValidRedirect(String redirectUrl) {
        if (redirectUrl == null || redirectUrl.isBlank()) {
            return false;
        }
        String origin = extractOrigin(redirectUrl);
        if (origin == null) {
            return false;
        }
        String normalizedOrigin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        return oAuthProperties.frontendRedirectUris().stream()
                .map(this::extractOrigin)
                .anyMatch(allowed -> normalizedOrigin.equals(allowed));
    }

    private String extractOrigin(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return null;
            }
            if (port != -1) {
                return scheme + "://" + host + ":" + port;
            }
            return scheme + "://" + host;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[PKCE_VERIFIER_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String computeCodeChallenge(String codeVerifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException(AuthError.KEY_GENERATION_FAILED);
        }
    }

    private String generateOneTimeCode() {
        byte[] bytes = new byte[OTC_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    record OAuthState(String codeVerifier, String nonce, String redirectUrl, String deviceInfo, String ipAddress) {
    }
}
