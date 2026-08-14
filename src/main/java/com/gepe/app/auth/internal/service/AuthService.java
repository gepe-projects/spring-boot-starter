package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.RoleType;
import com.gepe.app.auth.api.UserAuthenticated;
import com.gepe.app.auth.api.UserDto;
import com.gepe.app.auth.api.UserStatus;
import com.gepe.app.auth.internal.crypto.PasswordHasher;
import com.gepe.app.auth.internal.dto.RotatedToken;
import com.gepe.app.auth.internal.dto.TokenResponse;
import com.gepe.app.auth.internal.dto.TokenWithId;
import com.gepe.app.auth.internal.dto.UserDetailsDto;
import com.gepe.app.auth.internal.entity.AuthIdentity;
import com.gepe.app.auth.internal.entity.User;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.jwt.JwtService;
import com.gepe.app.auth.internal.repository.AuthIdentityRepository;
import com.gepe.app.auth.internal.repository.RoleRepository;
import com.gepe.app.auth.internal.repository.UserRepository;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.exception.ValidationException;
import com.gepe.app.platform.web.response.ValidationError;
import com.gepe.app.platform.web.security.AuthenticatedUser;
import com.gepe.app.user.api.ProfileService;
import com.gepe.app.user.api.UserProfileDto;
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
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordHasher passwordHasher;
    private final LoginRateLimiter loginRateLimiter;
    private final ApplicationEventPublisher events;
    private final ProfileService profileService;
    private final UserDetailsCache userDetailsCache;
    private final MessageHelper messageHelper;

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

        assertLoginAllowed(user);

        return issueTokens(user, deviceInfo, ipAddress);
    }

    // ── login google (backend OAuth) + account linking ──
    public TokenResponse googleLogin(String googleSub, String email, String displayName, String avatarUrl,
                                     String deviceInfo, String ipAddress) {
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
                user.addRole(roleRepository.getReferenceById(RoleType.USER.name()));
                userRepository.save(user);
            } else {
                user.markEmailVerified();
                userRepository.save(user);
                // emailVerified berubah → UserDto di cache /me ikut berubah
                userDetailsCache.evict(user.getId());
            }
            authIdentityRepository.save(new AuthIdentity(
                    user.getId(), AuthIdentity.PROVIDER_GOOGLE, googleSub, email, null));
            if (isNew) {
                // profil diinisialisasi user module (yang publish UserRegistered), satu transaksi dgn akun
                profileService.initialize(user.getId(), user.getEmail(), displayName, avatarUrl);
            }
        }

        assertLoginAllowed(user);

        return issueTokens(user, deviceInfo, ipAddress);
    }

    // ── register via credentials (email + password) ──
    public TokenResponse register(String email, String password, String displayName,
                                  String deviceInfo, String ipAddress) {
        loginRateLimiter.assertAllowed(email);

        if (userRepository.existsByEmail(email)) {
            loginRateLimiter.onFailure(email);
            throw new ValidationException(List.of(
                    new ValidationError("email", messageHelper.get("auth.email.already_registered"))));
        }
        User user = new User(email, null);
        user.addRole(roleRepository.getReferenceById(RoleType.USER.name()));
        userRepository.save(user);

        authIdentityRepository.save(new AuthIdentity(
                user.getId(), AuthIdentity.PROVIDER_CREDENTIALS, email, email,
                passwordHasher.hash(password)));

        loginRateLimiter.onSuccess(email);
        // displayName dari form register → profil tidak lahir kosong ("" / blank = null)
        profileService.initialize(user.getId(), user.getEmail(), blankToNull(displayName), null);
        return issueTokens(user, deviceInfo, ipAddress);
    }

    // ── me: identitas lengkap (auth, dari DB) + profil (user module via api) ──
    public UserDetailsDto me(AuthenticatedUser user) {
        return userDetailsCache.get(user.userId()).orElseGet(() -> {
            UserDetailsDto dto = loadUserDetails(user.userId());
            userDetailsCache.put(user.userId(), dto);
            return dto;
        });
    }

    // ── admin: ganti status akun (suspend/disable/activate) ──
    public void changeStatus(UUID userId, UserStatus newStatus) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ServiceException(AuthError.USER_NOT_FOUND));
        user.changeStatus(newStatus);
        userRepository.save(user);
        // status berubah → UserDto di cache /me ikut berubah (idempotent, aman di rollback)
        userDetailsCache.evict(userId);
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

        // Akun non-ACTIVE tidak boleh memperpanjang sesi — token refresh (yang sudah dirotasi
        // di atas) ikut di-rollback karena satu transaksi, jadi sesi tidak hangus sia-sia.
        assertLoginAllowed(user);

        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), RoleResolver.effectiveRoles(user)).serialize();

        return new TokenResponse(accessToken, rotated.raw(), rotated.id(), rotated.sessionId(),
                toUserDto(user));
    }

    // ── logout → hanya revoke session (AT stateless tetap valid sampai expiry) ──
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeByIdByHash(rawRefreshToken);
    }

    private TokenResponse issueTokens(User user, String deviceInfo, String ipAddress) {
        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), RoleResolver.effectiveRoles(user)).serialize();

        TokenWithId rt = refreshTokenService.issue(user.getId(), deviceInfo, ipAddress, Duration.ofDays(30));

        events.publishEvent(new UserAuthenticated(user.getId(), user.getEmail()));
        return new TokenResponse(accessToken, rt.raw(), rt.id(), rt.id(), toUserDto(user));
    }

    private UserDetailsDto loadUserDetails(UUID userId) {
        User userEntity = userRepository.findById(userId)
                .orElseThrow(() -> new ServiceException(AuthError.USER_NOT_FOUND));
        UserProfileDto profile = profileService.findByUserId(userId).orElse(null);
        return new UserDetailsDto(toUserDto(userEntity), profile);
    }

    /**
     * Hanya akun {@code ACTIVE} yang boleh autentikasi (login credentials, OAuth, refresh).
     * Status lain ditolak dengan error yang spesifik per status.
     */
    private void assertLoginAllowed(User user) {
        if (user.getStatus() == UserStatus.ACTIVE) {
            return;
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ServiceException(AuthError.ACCOUNT_SUSPENDED);
        }
        throw new ServiceException(AuthError.ACCOUNT_DISABLED);
    }

    private UserDto toUserDto(User user) {
        return new UserDto(user.getId(), user.getEmail(),
                user.getEmailVerifiedAt() != null, user.getStatus(), RoleResolver.effectiveRoles(user));
    }

    /** String kosong/blank = null (profil tidak menyimpan string kosong). */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
