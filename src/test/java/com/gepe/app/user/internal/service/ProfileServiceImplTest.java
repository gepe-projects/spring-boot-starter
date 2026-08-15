package com.gepe.app.user.internal.service;

import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.support.Uuidv7;
import com.gepe.app.user.api.dto.UserProfileDto;
import com.gepe.app.user.api.event.ProfileUpdatedEvent;
import com.gepe.app.user.api.event.UserRegisteredEvent;
import com.gepe.app.user.internal.dto.UpdateProfileRequest;
import com.gepe.app.user.internal.entity.UserProfile;
import com.gepe.app.user.internal.exception.UserProfileError;
import com.gepe.app.user.internal.repository.UserProfileRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceImplTest {

    @Mock
    UserProfileRepository profileRepository;
    @Mock
    ApplicationEventPublisher events;

    ProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProfileServiceImpl(profileRepository, events);
    }

    @Test
    void initializeCreatesProfileAndPublishesEvent() {
        UUID userId = Uuidv7.generate();
        when(profileRepository.existsById(userId)).thenReturn(false);

        service.initialize(userId, "a@b.com", "Nama", "https://pic.example.com/a.png");

        verify(profileRepository).saveAndFlush(argThat(profile ->
                profile.getUserId().equals(userId)
                        && "Nama".equals(profile.getDisplayName())
                        && "https://pic.example.com/a.png".equals(profile.getAvatarUrl())));
        verify(events).publishEvent(new UserRegisteredEvent(
                userId, "a@b.com", "Nama", "https://pic.example.com/a.png"));
    }

    @Test
    void initializeIsIdempotent() {
        UUID userId = Uuidv7.generate();
        when(profileRepository.existsById(userId)).thenReturn(true);

        service.initialize(userId, "a@b.com", null, null);

        verify(profileRepository, never()).saveAndFlush(any());
        verify(profileRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void initializeIgnoresConcurrentDuplicateInsert() {
        UUID userId = Uuidv7.generate();
        when(profileRepository.existsById(userId)).thenReturn(false);
        when(profileRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique "
                        + "constraint \"pk_profile\""));

        service.initialize(userId, "a@b.com", null, null);

        // balapan antar instance: insert kalah → anggap sudah dibuat instance lain, TANPA event
        verify(events, never()).publishEvent(any());
    }

    @Test
    void updateAppliesNonNullFieldsAndClearsEmptyStrings() {
        UUID userId = Uuidv7.generate();
        UserProfile existing = new UserProfile(userId, "Old Name", "https://old.example.com/a.png");
        when(profileRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(profileRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateProfileRequest(
                "New Name", null, "", null, null, null, null, null, "Asia/Jakarta", null);
        UserProfileDto dto = service.update(userId, request);

        assertThat(dto.displayName()).isEqualTo("New Name");
        assertThat(dto.avatarUrl()).isNull(); // "" → clear
        assertThat(dto.timezone()).isEqualTo("Asia/Jakarta");
        assertThat(existing.getNickname()).isNull(); // field null → tidak diubah
        // update sukses → publish ProfileUpdatedEvent (konsumen: evict cache GET /auth/me)
        verify(events).publishEvent(new ProfileUpdatedEvent(userId));
    }

    @Test
    void updateRejectsTakenNickname() {
        UUID userId = Uuidv7.generate();
        UserProfile existing = new UserProfile(userId, null, null);
        when(profileRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(profileRepository.existsByNicknameAndUserIdNot("taken", userId)).thenReturn(true);

        var request = new UpdateProfileRequest(null, "taken", null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.update(userId, request))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserProfileError.NICKNAME_TAKEN));
    }

    @Test
    void updateMapsUniqueViolationToConflict() {
        UUID userId = Uuidv7.generate();
        UserProfile existing = new UserProfile(userId, null, "old-nick");
        when(profileRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(profileRepository.existsByNicknameAndUserIdNot(any(), any())).thenReturn(false);
        when(profileRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique "
                        + "constraint \"uq_profile_nickname\""));

        var request = new UpdateProfileRequest(null, "new-nick", null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.update(userId, request))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserProfileError.NICKNAME_TAKEN));
    }

    @Test
    void updateCreatesProfileWhenMissing() {
        UUID userId = Uuidv7.generate();
        when(profileRepository.findById(userId)).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateProfileRequest("Solo", null, null, null, null, null, null, null, null, null);
        UserProfileDto dto = service.update(userId, request);

        assertThat(dto.displayName()).isEqualTo("Solo");
        verify(profileRepository).save(any());            // backfill
        verify(profileRepository).saveAndFlush(any());    // simpan final
    }
}
