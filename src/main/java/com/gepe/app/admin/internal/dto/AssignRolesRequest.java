package com.gepe.app.admin.internal.dto;

import com.gepe.app.auth.api.RoleType;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

public record AssignRolesRequest(@NotEmpty Set<RoleType> roles) {}
