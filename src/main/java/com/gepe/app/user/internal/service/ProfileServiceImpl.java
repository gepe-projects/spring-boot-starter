package com.gepe.app.user.internal.service;

import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.user.api.ProfileService;
import com.gepe.app.user.api.ProfileUpdated;
import com.gepe.app.user.api.UserProfileDto;
import com.gepe.app.user.api.UserRegistered;
import com.gepe.app.user.internal.dto.UpdateProfileRequest;
import com.gepe.app.user.internal.entity.UserProfile;
import com.gepe.app.user.internal.exception.UserProfileError;
import com.gepe.app.user.internal.repository.UserProfileRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case module user. Semua method @Transactional (class-level):
 * - {@code initialize} dipanggil auth di dalam transaksi registrasi → REQUIRED propagation
 *   bergabung ke transaksi yang sama, jadi pembuatan profil atomik dengan pembuatan akun
 *   (bukan eventual consistency — sengaja, supaya tidak ada race saat GET profil);
 * - {@code update} read-modify-write wajib satu transaksi + flush eksplisit supaya pelanggaran
 *   unique index nickname terdeteksi di dalam blok try/catch (aman lintas instance).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProfileServiceImpl implements ProfileService {

    private final UserProfileRepository profileRepository;
    private final ApplicationEventPublisher events;

    @Override
    public Optional<UserProfileDto> findByUserId(UUID userId) {
        return profileRepository.findById(userId).map(this::toDto);
    }

    @Override
    public void initialize(UUID userId, String email, String displayName, String avatarUrl) {
        if (profileRepository.existsById(userId)) {
            return; // idempotent — jangan timpa profil yang sudah ada / sudah diedit user
        }
        try {
            profileRepository.saveAndFlush(new UserProfile(userId, displayName, avatarUrl));
        } catch (DataIntegrityViolationException e) {
            // Race lintas instance: baris profil sudah dibuat instance lain (PK user_id di DB).
            // Satu-satunya constraint yang bisa dilanggar di sini adalah PK → anggap sudah selesai.
            return;
        }
        events.publishEvent(new UserRegistered(userId, email, displayName, avatarUrl));
    }

    /** Update profil (get-or-create: backfill user lama yang belum punya baris profil). */
    public UserProfileDto update(UUID userId, UpdateProfileRequest request) {
        UserProfile profile = profileRepository.findById(userId)
                .orElseGet(() -> profileRepository.save(new UserProfile(userId, null, null)));

        if (request.displayName() != null) profile.setDisplayName(blankToNull(request.displayName()));
        if (request.nickname() != null) {
            String nickname = request.nickname();
            if (profileRepository.existsByNicknameAndUserIdNot(nickname, userId)) {
                throw new ServiceException(UserProfileError.NICKNAME_TAKEN);
            }
            profile.setNickname(blankToNull(nickname));
        }
        if (request.avatarUrl() != null) profile.setAvatarUrl(blankToNull(request.avatarUrl()));
        if (request.bio() != null) profile.setBio(blankToNull(request.bio()));
        if (request.dateOfBirth() != null) profile.setDateOfBirth(request.dateOfBirth());
        if (request.gender() != null) profile.setGender(request.gender());
        if (request.phone() != null) profile.setPhone(blankToNull(request.phone()));
        if (request.location() != null) profile.setLocation(blankToNull(request.location()));
        if (request.timezone() != null) profile.setTimezone(request.timezone());
        if (request.locale() != null) profile.setLocale(request.locale());
        profile.touch();

        try {
            // flush eksplisit: pelanggaran unique index nickname baru muncul saat flush,
            // bukan saat commit — harus terjadi di dalam try/catch ini supaya jadi 409.
            profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            if (isNicknameConflict(e)) {
                throw new ServiceException(UserProfileError.NICKNAME_TAKEN);
            }
            throw e;
        }
        // Profil berubah → module auth harus evict cache komposit GET /auth/me.
        // Event AFTER_COMMIT + idempotent (evict), dikirim lewat event publication registry.
        events.publishEvent(new ProfileUpdated(userId));
        return toDto(profile);
    }

    private UserProfileDto toDto(UserProfile p) {
        return new UserProfileDto(
                p.getUserId(), p.getDisplayName(), p.getNickname(), p.getAvatarUrl(), p.getBio(),
                p.getDateOfBirth(), p.getGender(), p.getPhone(), p.getLocation(),
                p.getTimezone(), p.getLocale(), p.getCreatedAt(), p.getUpdatedAt());
    }

    /** String kosong = clear → null. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Pengecekan backstop (pre-check bisa kalah balapan antar instance): unik di kolom nickname
     * dijamin unique index {@code uq_profile_nickname} di DB — nama constraint muncul di pesan
     * exception Postgres. Violation lain (mis. PK) diteruskan apa adanya.
     */
    private static boolean isNicknameConflict(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("uq_profile_nickname");
    }
}
