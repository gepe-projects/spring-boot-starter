package com.gepe.app.auth.internal.delivery.http;

import com.gepe.app.auth.internal.dto.RotatedKeyResponse;
import com.gepe.app.auth.internal.service.SigningKeyRotationService;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.web.api.ApiVersions;
import com.gepe.app.platform.web.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/" + ApiVersions.CURRENT + "/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
class AdminController {

    private final SigningKeyRotationService rotationService;
    private final MessageHelper messageHelper;

    @PostMapping("/keys/rotate")
    ResponseEntity<ApiResponse<RotatedKeyResponse>> rotateSigningKey() {
        RotatedKeyResponse result = rotationService.rotate();
        return ResponseEntity.ok(new ApiResponse<>(
                messageHelper.get("auth.keys_rotated_success"), result));
    }
}
