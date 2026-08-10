package com.gepe.app.auth.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(
        String accessToken,
        String refreshToken,
        UUID refreshTokenId,
        UUID sessionId,
        UUID userId) {
}
