package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.dto.RoleType;
import com.gepe.app.auth.api.dto.UserDto;
import com.gepe.app.auth.api.dto.UserStatus;
import com.gepe.app.auth.internal.crypto.PasswordHasher;
import com.gepe.app.auth.internal.dto.RotatedToken;
import com.gepe.app.auth.internal.dto.TokenResponse;
import com.gepe.app.auth.internal.dto.TokenWithId;
import com.gepe.app.auth.internal.dto.UserDetailsDto;
import com.gepe.app.auth.internal.entity.AuthIdentity;
import com.gepe.app.auth.internal.entity.Role;
import com.gepe.app.auth.internal.entity.User;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.jwt.JwtService;
import com.gepe.app.auth.internal.repository.AuthIdentityRepository;
import com.gepe.app.auth.internal.repository.RoleRepository;
import com.gepe.app.auth.internal.repository.UserRepository;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.support.Uuidv7;
import com.gepe.app.platform.web.security.AuthenticatedUser;
import com.gepe.app.user.api.ProfileService;
import com.gepe.app.user.api.dto.UserProfileDto;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    AuthIdentityRepository authIdentityRepository;
    @Mock
    RoleRepository roleRepository;
    @Mock
    RefreshTokenService refreshTokenService;
    @Mock
    JwtService jwtService;
    @Mock
    PasswordHasher passwordHasher;
    @Mock
    LoginRateLimiter loginRateLimiter;
    @Mock
    ApplicationEventPublisher events;
    @Mock
    ProfileService profileService;
    @Mock
    UserDetailsCache userDetailsCache;
    @Mock
    MessageHelper messageHelper;

    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, authIdentityRepository, roleRepository,
                refreshTokenService, jwtService, passwordHasher, loginRateLimiter, events,
                profileService, userDetailsCache, messageHelper);
    }

    @Test
    void loginRejectsSuspendedAccount() {
        UUID userId = Uuidv7.generate();
        User user = new User("a@b.com", null);
        user.changeStatus(UserStatus.SUSPENDED);
        mockCredentialsIdentity(userId, "a@b.com");
        when(passwordHasher.matches("secret", "hash")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login("a@b.com", "secret", "ua", "1.2.3.4"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.ACCOUNT_SUSPENDED));
        verify(jwtService, never()).issueAccessToken(any(), any(), any());
    }

    @Test
    void loginRejectsDisabledAccount() {
        UUID userId = Uuidv7.generate();
        User user = new User("a@b.com", null);
        user.changeStatus(UserStatus.DISABLED);
        mockCredentialsIdentity(userId, "a@b.com");
        when(passwordHasher.matches("secret", "hash")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login("a@b.com", "secret", "ua", "1.2.3.4"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.ACCOUNT_DISABLED));
        verify(jwtService, never()).issueAccessToken(any(), any(), any());
    }

    @Test
    void googleLoginRejectsSuspendedAccount() {
        UUID userId = Uuidv7.generate();
        User user = new User("a@b.com", Instant.now());
        user.changeStatus(UserStatus.SUSPENDED);
        when(authIdentityRepository.findByProviderAndProviderId(AuthIdentity.PROVIDER_GOOGLE, "google-sub"))
                .thenReturn(Optional.of(new AuthIdentity(
                        userId, AuthIdentity.PROVIDER_GOOGLE, "google-sub", "a@b.com", null)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() ->
                service.googleLogin("google-sub", "a@b.com", "A", "pic", "ua", "1.2.3.4"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.ACCOUNT_SUSPENDED));
        verify(jwtService, never()).issueAccessToken(any(), any(), any());
    }

    @Test
    void refreshRejectsSuspendedAccount() {
        UUID userId = Uuidv7.generate();
        User user = new User("a@b.com", null);
        user.changeStatus(UserStatus.SUSPENDED);
        when(refreshTokenService.rotate("raw", "ua", "1.2.3.4"))
                .thenReturn(new RotatedToken(Uuidv7.generate(), "new-raw", userId, Uuidv7.generate()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.refresh("raw", "ua", "1.2.3.4"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.ACCOUNT_SUSPENDED));
        verify(jwtService, never()).issueAccessToken(any(), any(), any());
    }

    @Test
    void loginSucceedsForActiveAccount() {
        UUID userId = Uuidv7.generate();
        User user = new User("a@b.com", null); // default ACTIVE
        user.setId(userId);
        mockCredentialsIdentity(userId, "a@b.com");
        when(passwordHasher.matches("secret", "hash")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        SignedJWT signed = mock(SignedJWT.class);
        when(signed.serialize()).thenReturn("jwt");
        when(jwtService.issueAccessToken(eq(userId), eq("a@b.com"), any())).thenReturn(signed);
        when(refreshTokenService.issue(eq(userId), eq("ua"), eq("1.2.3.4"), any()))
                .thenReturn(new TokenWithId(Uuidv7.generate(), "rt"));

        TokenResponse response = service.login("a@b.com", "secret", "ua", "1.2.3.4");

        assertThat(response.user().status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.accessToken()).isEqualTo("jwt");
    }

    @Test
    void meReadsThroughCacheAndFallsBackToDbOnMiss() {
        UUID userId = Uuidv7.generate();
        User user = new User("a@b.com", null);
        user.setId(userId);
        UserDto userDto = new UserDto(userId, "a@b.com", false, UserStatus.ACTIVE, List.of());
        UserProfileDto profile = new UserProfileDto(
                userId, "Nama", null, null, null, null, null, null, null, "UTC", "en",
                Instant.now(), Instant.now());
        UserDetailsDto expected = new UserDetailsDto(userDto, profile);

        when(userDetailsCache.get(userId)).thenReturn(Optional.empty(), Optional.of(expected));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(profileService.findByUserId(userId)).thenReturn(Optional.of(profile));

        AuthenticatedUser authUser = new AuthenticatedUser(userId, "a@b.com", List.of());

        UserDetailsDto first = service.me(authUser);
        UserDetailsDto second = service.me(authUser);

        assertThat(first).isEqualTo(expected);
        assertThat(second).isEqualTo(expected);
        verify(userRepository, times(1)).findById(userId); // hit kedua tidak sentuh DB
        verify(userDetailsCache).put(userId, expected);
    }

    @Test
    void registerInitializesProfileWithDisplayName() {
        String email = "a@b.com";
        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(roleRepository.getReferenceById(RoleType.USER.name())).thenReturn(mock(Role.class));
        when(passwordHasher.hash("secret1234")).thenReturn("hash");
        stubTokenIssuance();

        service.register(email, "secret1234", "Nama User", "ua", "1.2.3.4");

        verify(profileService).initialize(any(), eq(email), eq("Nama User"), isNull());
    }

    @Test
    void registerNormalizesBlankDisplayNameToNull() {
        String email = "a@b.com";
        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(roleRepository.getReferenceById(RoleType.USER.name())).thenReturn(mock(Role.class));
        when(passwordHasher.hash("secret1234")).thenReturn("hash");
        stubTokenIssuance();

        service.register(email, "secret1234", "   ", "ua", "1.2.3.4");

        verify(profileService).initialize(any(), eq(email), isNull(), isNull());
    }

    private void stubTokenIssuance() {
        SignedJWT signed = mock(SignedJWT.class);
        when(signed.serialize()).thenReturn("jwt");
        when(jwtService.issueAccessToken(any(), any(), any())).thenReturn(signed);
        when(refreshTokenService.issue(any(), any(), any(), any()))
                .thenReturn(new TokenWithId(Uuidv7.generate(), "rt"));
    }

    private void mockCredentialsIdentity(UUID userId, String email) {
        when(authIdentityRepository.findByProviderAndProviderId(AuthIdentity.PROVIDER_CREDENTIALS, email))
                .thenReturn(Optional.of(new AuthIdentity(
                        userId, AuthIdentity.PROVIDER_CREDENTIALS, email, email, "hash")));
    }
}
