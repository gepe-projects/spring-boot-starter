package com.gepe.app.admin.internal.dto;

import com.gepe.app.auth.api.dto.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(@NotNull UserStatus status) {}
