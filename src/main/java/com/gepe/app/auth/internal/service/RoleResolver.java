package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.internal.entity.Role;
import com.gepe.app.auth.internal.entity.User;
import java.util.List;

final class RoleResolver {

    private RoleResolver() {}

    static List<String> effectiveRoles(User user) {
        return user.getRoles().stream().map(Role::getName).toList();
    }
}
