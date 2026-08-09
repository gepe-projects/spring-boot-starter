package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.UserAuthenticated;
import com.gepe.app.auth.api.UserRegistered;
import com.gepe.app.auth.internal.crypto.PasswordHasher;
import com.gepe.app.auth.internal.dto.RotatedToken;
import com.gepe.app.auth.internal.dto.TokenResponse;
import com.gepe.app.auth.internal.dto.TokenWithId;
import com.gepe.app.auth.internal.entity.AuthIdentity;
import com.gepe.app.auth.internal.entity.Role;
import com.gepe.app.auth.internal.entity.User;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.jwt.JwtService;
import com.gepe.app.auth.internal.repository.AuthIdentityRepository;
import com.gepe.app.auth.internal.repository.UserRepository;
import com.gepe.app.platform.exception.ServiceException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final AuthIdentityRepository authIdentityRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordHasher passwordHasher;
    private final LoginRateLimiter loginRateLimiter;
    private final ApplicationEventPublisher events;

    // ── login credentials ──
    public TokenResponse login(String email, String password, String deviceInfo, String ipAddress) {
        loginRateLimiter.assertAllowed(email);

        AuthIdentity cred = authIdentityRepository
                .findByProviderAndProviderId(AuthIdentity.PROVIDER_CREDENTIALS, email)
                .orElseThrow(() -> {
                    loginRateLimiter.onFailure(email);
                    return new ServiceException(AuthError.INVALID_CREDENTIALS);
                });

        if (!passwordHasher.matches(password, cred.getPasswordHash())) {
            loginRateLimiter.onFailure(email);
            throw new ServiceException(AuthError.INVALID_CREDENTIALS);
        }

        loginRateLimiter.onSuccess(email);

        User user = userRepository.findById(cred.getUserId())
                .orElseThrow(() -> new ServiceException(AuthError.INVALID_CREDENTIALS));

        return issueTokens(user, deviceInfo, ipAddress);
    }

    // ── login google (backend OAuth) + account linking ──
    public TokenResponse googleLogin(String googleSub, String email, String deviceInfo, String ipAddress) {
        AuthIdentity existing = authIdentityRepository
                .findByProviderAndProviderId(AuthIdentity.PROVIDER_GOOGLE, googleSub)
                .orElse(null);

        User user;
        if (existing != null) {
            user = userRepository.findById(existing.getUserId())
                    .orElseThrow(() -> new ServiceException(AuthError.IDENTITY_NOT_FOUND));
        } else {
            user = userRepository.findByEmail(email).orElse(null);
            boolean isNew = user == null;
            if (isNew) {
                user = new User(email, Instant.now());
                userRepository.save(user);
            } else {
                user.markEmailVerified();
                userRepository.save(user);
            }
            authIdentityRepository.save(new AuthIdentity(
                    user.getId(), AuthIdentity.PROVIDER_GOOGLE, googleSub, email, null));
            if (isNew) {
                events.publishEvent(new UserRegistered(user.getId(), user.getEmail()));
            }
        }

        return issueTokens(user, deviceInfo, ipAddress);
    }

    // ── register via credentials (email + password) ──
    public TokenResponse register(String email, String password, String deviceInfo, String ipAddress) {
        loginRateLimiter.assertAllowed(email);

        if (userRepository.existsByEmail(email)) {
            loginRateLimiter.onFailure(email);
            throw new ServiceException(AuthError.EMAIL_ALREADY_LINKED);
        }
        User user = new User(email, null);
        userRepository.save(user);

        authIdentityRepository.save(new AuthIdentity(
                user.getId(), AuthIdentity.PROVIDER_CREDENTIALS, email, email,
                passwordHasher.hash(password)));

        loginRateLimiter.onSuccess(email);
        events.publishEvent(new UserRegistered(user.getId(), user.getEmail()));
        return issueTokens(user, deviceInfo, ipAddress);
    }

    // ── set password (untuk user yang login via google saja) → binding ke credentials ──
    public void setPassword(UUID userId, String newPassword) {
        if (authIdentityRepository.existsByUserIdAndProvider(userId, AuthIdentity.PROVIDER_CREDENTIALS)) {
            throw new ServiceException(AuthError.IDENTITY_EXISTS);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ServiceException(AuthError.IDENTITY_NOT_FOUND));
        authIdentityRepository.save(new AuthIdentity(
                userId, AuthIdentity.PROVIDER_CREDENTIALS, user.getEmail(), user.getEmail(),
                passwordHasher.hash(newPassword)));
    }

    // ── refresh ──
    public TokenResponse refresh(String rawRefreshToken, String deviceInfo, String ipAddress) {
        RotatedToken rotated = refreshTokenService.rotate(rawRefreshToken, deviceInfo, ipAddress);

        User user = userRepository.findById(rotated.userId())
                .orElseThrow(() -> new ServiceException(AuthError.IDENTITY_NOT_FOUND));

        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), resolveRoles(user)).serialize();

        return new TokenResponse(accessToken, rotated.raw(), rotated.id(), rotated.sessionId(), user.getId());
    }

    // ── logout → hanya revoke session (AT stateless tetap valid sampai expiry) ──
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeByIdByHash(rawRefreshToken);
    }

    private TokenResponse issueTokens(User user, String deviceInfo, String ipAddress) {
        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), resolveRoles(user)).serialize();

        TokenWithId rt = refreshTokenService.issue(user.getId(), deviceInfo, ipAddress, Duration.ofDays(30));

        events.publishEvent(new UserAuthenticated(user.getId(), user.getEmail()));
        return new TokenResponse(accessToken, rt.raw(), rt.id(), rt.id(), user.getId());
    }

    private List<String> resolveRoles(User user) {
        if (user.getRoles().isEmpty()) {
            user.getRoles().add(Role.USER);
        }
        return user.getRoles().stream().map(Role::name).toList();
    }
}
