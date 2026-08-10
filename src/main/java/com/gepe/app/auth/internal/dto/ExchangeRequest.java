package com.gepe.app.auth.internal.dto;

import jakarta.validation.constraints.NotBlank;

public record ExchangeRequest(@NotBlank String code) {
}
