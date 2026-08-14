package com.gepe.app.auth.internal.delivery.http;

import com.gepe.app.auth.internal.dto.*;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.service.AuthService;
import com.gepe.app.auth.internal.service.RefreshTokenService;
import com.gepe.app.auth.internal.service.SessionService;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.pagination.CursorPage;
import com.gepe.app.platform.web.api.ApiVersions;
import com.gepe.app.platform.web.context.RequestContext;
import com.gepe.app.platform.web.response.ApiResponse;
import com.gepe.app.platform.web.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/" + ApiVersions.CURRENT + "/auth")
@RequiredArgsConstructor
class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final RefreshTokenService refreshTokenService;
    private final MessageHelper messageHelper;

    @PostMapping("/register")
    ResponseEntity<ApiResponse<TokenResponse>> register(@Valid @RequestBody RegisterRequest request,
                                                        HttpServletRequest http) {
        TokenResponse tokens = authService.register(request.email(), request.password(), request.displayName(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.login_success"), tokens));
    }

    @PostMapping("/login")
    ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest http) {
        TokenResponse tokens = authService.login(request.email(), request.password(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.login_success"), tokens));
    }

    @PostMapping("/refresh")
    ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request,
                                                       HttpServletRequest http) {
        TokenResponse tokens = authService.refresh(request.refreshToken(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.refresh_success"), tokens));
    }

    @PostMapping("/logout")
    ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.logout_success"), null));
    }

    @PostMapping("/password")
    ResponseEntity<ApiResponse<Void>> setPassword(@Valid @RequestBody SetPasswordRequest request) {
        UUID userId = RequestContext.getCurrentUserId();
        authService.setPassword(userId, request.newPassword());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.password_set_success"), null));
    }

    @GetMapping("/me")
    ResponseEntity<ApiResponse<UserDetailsDto>> me() {
        AuthenticatedUser user = RequestContext.getCurrentUser();
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("common.success"), authService.me(user)));
    }

    @GetMapping("/sessions")
    ResponseEntity<ApiResponse<CursorPage<SessionInfo>>> sessions(
            @RequestHeader(value = "X-Refresh-Token", required = false) String currentRefreshToken,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        UUID userId = RequestContext.getCurrentUserId();
        UUID currentTokenId = currentRefreshToken != null
                ? resolveTokenId(currentRefreshToken)
                : null;
        return ResponseEntity.ok(new ApiResponse<>(null,
                sessionService.listActive(userId, currentTokenId, cursor, limit)));
    }

    @DeleteMapping("/sessions/{refreshTokenId}")
    ResponseEntity<ApiResponse<Void>> revokeSession(
            @PathVariable UUID refreshTokenId,
            @RequestHeader(value = "X-Refresh-Token", required = false) String currentRefreshToken) {
        UUID userId = RequestContext.getCurrentUserId();
        UUID currentTokenId = currentRefreshToken != null ? resolveTokenId(currentRefreshToken) : null;
        sessionService.revokeSession(userId, refreshTokenId, currentTokenId);
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.sessions_revoked_success"), null));
    }

    @DeleteMapping("/sessions")
    ResponseEntity<ApiResponse<Void>> revokeOthers(
            @RequestHeader("X-Refresh-Token") String currentRefreshToken) {
        UUID userId = RequestContext.getCurrentUserId();
        UUID currentTokenId = resolveTokenId(currentRefreshToken);
        sessionService.revokeAllExcept(userId, currentTokenId);
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.sessions_revoked_success"), null));
    }

    private UUID resolveTokenId(String refreshToken) {
        return refreshTokenService.findIdByRawToken(refreshToken)
                .orElseThrow(() -> new ServiceException(AuthError.CURRENT_TOKEN_REQUIRED));
    }
}
