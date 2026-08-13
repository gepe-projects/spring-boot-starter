package com.gepe.app.auth.internal.delivery.http;

import com.gepe.app.auth.internal.dto.ExchangeRequest;
import com.gepe.app.auth.internal.dto.GoogleAuthStartResponse;
import com.gepe.app.auth.internal.dto.TokenResponse;
import com.gepe.app.auth.internal.oauth.GoogleOAuthService;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.web.api.ApiVersions;
import com.gepe.app.platform.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class GoogleController {

    private final GoogleOAuthService googleOAuthService;
    private final MessageHelper messageHelper;

    @GetMapping("/api/" + ApiVersions.CURRENT + "/auth/oauth/google")
    ResponseEntity<ApiResponse<GoogleAuthStartResponse>> begin(
            @RequestParam("redirect_url") String redirectUrl,
            HttpServletRequest request) {

        String googleUrl = googleOAuthService.begin(
                redirectUrl,
                request.getHeader("User-Agent"),
                request.getRemoteAddr());

        return ResponseEntity.ok(new ApiResponse<>(null,
                new GoogleAuthStartResponse(googleUrl)));
    }

    @GetMapping("/auth/oauth/google/callback")
    ResponseEntity<Void> callback(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code", required = false) String code) {

        URI target = (error != null || code == null)
                ? googleOAuthService.errorRedirect(state, error != null ? error : "missing_code")
                : googleOAuthService.callback(state, code);

        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }

    @PostMapping("/api/" + ApiVersions.CURRENT + "/auth/oauth/exchange")
    ResponseEntity<ApiResponse<TokenResponse>> exchange(
            @Valid @RequestBody ExchangeRequest request) {

        TokenResponse tokens = googleOAuthService.exchange(request.code());
        return ResponseEntity.ok(new ApiResponse<>(messageHelper.get("auth.login_success"), tokens));
    }
}
