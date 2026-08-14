package com.gepe.app.user.internal.dto;

import com.gepe.app.user.api.Gender;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.hibernate.validator.constraints.URL;

/**
 * Partial update profil: field null = tidak diubah; string kosong = hapus/clear.
 * (website sengaja tidak ada di profil.)
 */
public record UpdateProfileRequest(
        @Size(max = 120) String displayName,
        @Pattern(regexp = "^[a-z0-9][a-z0-9_.-]{2,49}$")
        String nickname,
        @Size(max = 2048) @URL String avatarUrl,
        @Size(max = 500) String bio,
        @Past LocalDate dateOfBirth,
        Gender gender,
        @Size(max = 30) String phone,
        @Size(max = 255) String location,
        @Size(max = 64) String timezone,
        @Size(max = 10) String locale) {}
