package com.gepe.app.admin.internal.delivery.http;

import com.gepe.app.admin.internal.service.AdminKeyService;
import com.gepe.app.auth.api.RotatedKeyResponse;
import com.gepe.app.auth.api.SigningKeyInfo;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.web.api.ApiVersions;
import com.gepe.app.platform.web.context.RequestContext;
import com.gepe.app.platform.web.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/" + ApiVersions.CURRENT + "/admin/keys")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
class AdminKeyController {

    private final AdminKeyService adminKeyService;
    private final MessageHelper messageHelper;

    @GetMapping
    ResponseEntity<ApiResponse<List<SigningKeyInfo>>> listKeys() {
        return ResponseEntity.ok(new ApiResponse<>(null, adminKeyService.listSigningKeys()));
    }

    @PostMapping("/rotate")
    ResponseEntity<ApiResponse<RotatedKeyResponse>> rotate() {
        RotatedKeyResponse result = adminKeyService.rotateSigningKey(RequestContext.getCurrentUserId());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("admin.keys_rotated_success"), result));
    }
}
