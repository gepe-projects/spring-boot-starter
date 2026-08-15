package com.gepe.app.user.internal.delivery.http;

import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.web.api.ApiVersions;
import com.gepe.app.platform.web.context.RequestContext;
import com.gepe.app.platform.web.response.ApiResponse;
import com.gepe.app.user.api.dto.UserProfileDto;
import com.gepe.app.user.internal.dto.UpdateProfileRequest;
import com.gepe.app.user.internal.service.ProfileServiceImpl;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint tulis profil milik user sendiri. Pembacaan profil digabung di GET /api/v1/auth/me
 * (module auth memakai user.api.ProfileService). userId diambil HANYA dari RequestContext —
 * tidak pernah dari body/path.
 */
@RestController
@RequestMapping("/api/" + ApiVersions.CURRENT + "/users/me/profile")
@RequiredArgsConstructor
class ProfileController {

    private final ProfileServiceImpl profileService;
    private final MessageHelper messageHelper;

    /** Semantik: field null = tidak diubah; string kosong = hapus/clear; nickname duplikat → 409. */
    @PatchMapping
    ResponseEntity<ApiResponse<UserProfileDto>> update(@Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = RequestContext.getCurrentUserId();
        UserProfileDto profile = profileService.update(userId, request);
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("user.profile_updated"), profile));
    }
}
