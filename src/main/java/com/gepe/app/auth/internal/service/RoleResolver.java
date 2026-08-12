package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.entity.Role;
import com.gepe.app.auth.internal.entity.User;
import java.util.List;
import java.util.Set;

final class RoleResolver {

    private RoleResolver() {}

    static List<String> effectiveRoles(User user) {
        Set<Role> roles = user.getRoles();
        if (roles.isEmpty()) {
            return List.of(Role.USER.name());
        }
        return roles.stream().map(Role::name).toList();
    }
}
