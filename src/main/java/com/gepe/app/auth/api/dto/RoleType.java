package com.gepe.app.auth.api.dto;

/**
 * Role platform — boundary enum public, dipakai {@code UserDto.roles}, DTO request
 * admin, dan entity internal {@code auth.roles}.
 */
public enum RoleType {
    USER,
    ADMIN,
    OPERATION,
    SUPER_ADMIN
}
