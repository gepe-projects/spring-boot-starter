package com.gepe.app.auth.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetPasswordRequest(
        @NotBlank @Size(min = 8, max = 72) String newPassword) {
}
