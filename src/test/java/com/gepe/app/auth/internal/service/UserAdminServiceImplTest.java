package com.gepe.app.auth.internal.service;

import com.gepe.app.auth.api.RoleType;
import com.gepe.app.auth.api.UserStatus;
import com.gepe.app.auth.internal.entity.Role;
import com.gepe.app.auth.internal.entity.User;
import com.gepe.app.auth.internal.exception.AuthError;
import com.gepe.app.auth.internal.repository.RefreshTokenRepository;
import com.gepe.app.auth.internal.repository.RoleRepository;
import com.gepe.app.auth.internal.repository.UserRepository;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.pagination.CursorPage;
import com.gepe.app.platform.support.Uuidv7;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceImplTest {

    @Mock
    UserRepository userRepository;
    @Mock
    RoleRepository roleRepository;
    @Mock
    RefreshTokenRepository refreshTokenRepository;
    @Mock
    UserDetailsCache userDetailsCache;

    UserAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserAdminServiceImpl(
                userRepository, roleRepository, refreshTokenRepository, userDetailsCache);
    }

    // ── helpers ──

    private User userWithRoles(String email, RoleType... roleTypes) {
        User user = new User(email, null);
        for (RoleType roleType : roleTypes) {
            user.addRole(new Role(roleType.name(), roleType.name()));
        }
        return user;
    }

    private void stubActor(UUID actorId, RoleType... roleTypes) {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(userWithRoles("actor@b.com", roleTypes)));
    }

    // ── changeStatus ──

    @Test
    void changeStatusUpdatesUserAndEvictsCache() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.SUPER_ADMIN);
        User target = userWithRoles("a@b.com", RoleType.USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(target));

        service.changeStatus(actorId, userId, UserStatus.SUSPENDED);

        assertThat(target.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(target.getStatusChangedAt()).isNotNull();
        verify(userRepository).save(target);
        verify(userDetailsCache).evict(userId);
        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
    }

    @Test
    void changeStatusRejectsSelfOperationForRegularUser() {
        UUID actorId = Uuidv7.generate();
        User target = userWithRoles("a@b.com", RoleType.USER);
        target.setId(actorId);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.changeStatus(actorId, actorId, UserStatus.SUSPENDED))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.SELF_OPERATION_FORBIDDEN));
        verify(userDetailsCache, never()).evict(any());
    }

    @Test
    void changeStatusRejectsUnknownUser() {
        UUID userId = Uuidv7.generate();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeStatus(Uuidv7.generate(), userId, UserStatus.SUSPENDED))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.USER_NOT_FOUND));
        verify(userDetailsCache, never()).evict(any());
    }

    @Test
    void changeStatusRejectsAdminModifyingAnotherAdmin() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.ADMIN);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(userWithRoles("admin2@b.com", RoleType.ADMIN)));

        assertThatThrownBy(() -> service.changeStatus(actorId, userId, UserStatus.SUSPENDED))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.INSUFFICIENT_PRIVILEGE));
        verify(userDetailsCache, never()).evict(any());
    }

    @Test
    void changeStatusRejectsAdminModifyingSuperAdmin() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.ADMIN);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(userWithRoles("root@b.com", RoleType.SUPER_ADMIN)));

        assertThatThrownBy(() -> service.changeStatus(actorId, userId, UserStatus.SUSPENDED))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.INSUFFICIENT_PRIVILEGE));
    }

    @Test
    void changeStatusAllowsSuperAdminToModifyItself() {
        UUID actorId = Uuidv7.generate();
        User self = userWithRoles("root@b.com", RoleType.SUPER_ADMIN);
        self.setId(actorId);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(self));
        when(userRepository.countUsersWithRoleAndStatus(RoleType.SUPER_ADMIN.name(), UserStatus.ACTIVE))
                .thenReturn(2L);

        service.changeStatus(actorId, actorId, UserStatus.SUSPENDED);

        assertThat(self.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(userDetailsCache).evict(actorId);
    }

    @Test
    void changeStatusRejectsDisablingLastActiveSuperAdmin() {
        UUID actorId = Uuidv7.generate();
        User self = userWithRoles("root@b.com", RoleType.SUPER_ADMIN);
        self.setId(actorId);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(self));
        when(userRepository.countUsersWithRoleAndStatus(RoleType.SUPER_ADMIN.name(), UserStatus.ACTIVE))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.changeStatus(actorId, actorId, UserStatus.DISABLED))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.LAST_SUPER_ADMIN_STATUS));
        assertThat(self.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    // ── assignRoles ──

    @Test
    void assignRolesReplacesRolesEvictsCacheAndRevokesSessions() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.SUPER_ADMIN);
        User target = userWithRoles("a@b.com", RoleType.USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(target));
        when(roleRepository.findById(RoleType.ADMIN.name()))
                .thenReturn(Optional.of(new Role(RoleType.ADMIN.name(), "Admin")));

        service.assignRoles(actorId, userId, Set.of(RoleType.ADMIN));

        assertThat(target.getRoles()).extracting(Role::getName).containsExactly(RoleType.ADMIN.name());
        verify(userRepository).save(target);
        verify(userDetailsCache).evict(userId);
        verify(refreshTokenRepository).revokeAllForUser(eq(userId), any());
    }

    @Test
    void assignRolesAllowsSuperAdminToGrantSuperAdmin() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.SUPER_ADMIN);
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles("a@b.com", RoleType.USER)));
        when(roleRepository.findById(RoleType.SUPER_ADMIN.name()))
                .thenReturn(Optional.of(new Role(RoleType.SUPER_ADMIN.name(), "Root")));

        service.assignRoles(actorId, userId, Set.of(RoleType.SUPER_ADMIN));

        verify(refreshTokenRepository).revokeAllForUser(eq(userId), any());
    }

    @Test
    void assignRolesRejectsAdminGrantingAdminRole() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.ADMIN);
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles("a@b.com", RoleType.USER)));

        assertThatThrownBy(() -> service.assignRoles(actorId, userId, Set.of(RoleType.ADMIN)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.INSUFFICIENT_PRIVILEGE));
        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
    }

    @Test
    void assignRolesRejectsAdminGrantingSuperAdminRole() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.ADMIN);
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles("a@b.com", RoleType.USER)));

        assertThatThrownBy(() -> service.assignRoles(actorId, userId, Set.of(RoleType.SUPER_ADMIN)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.INSUFFICIENT_PRIVILEGE));
    }

    @Test
    void assignRolesRejectsEmptySet() {
        assertThatThrownBy(() -> service.assignRoles(Uuidv7.generate(), Uuidv7.generate(), Set.of()))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.ROLE_SET_EMPTY));
        verify(userRepository, never()).findById(any());
    }

    @Test
    void assignRolesRejectsSelfOperationForRegularUser() {
        UUID actorId = Uuidv7.generate();
        User target = userWithRoles("a@b.com", RoleType.USER);
        target.setId(actorId);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.assignRoles(actorId, actorId, Set.of(RoleType.ADMIN)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.SELF_OPERATION_FORBIDDEN));
    }

    @Test
    void assignRolesRejectsUnknownRole() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.SUPER_ADMIN);
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithRoles("a@b.com", RoleType.USER)));
        when(roleRepository.findById(RoleType.OPERATION.name())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignRoles(actorId, userId, Set.of(RoleType.OPERATION)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.ROLE_NOT_FOUND));
    }

    @Test
    void assignRolesRejectsRemovingLastAdmin() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.SUPER_ADMIN);
        User admin = userWithRoles("admin@b.com", RoleType.ADMIN);
        when(userRepository.findById(userId)).thenReturn(Optional.of(admin));
        when(roleRepository.findById(RoleType.USER.name()))
                .thenReturn(Optional.of(new Role(RoleType.USER.name(), "Regular user")));
        when(userRepository.countUsersWithRole(RoleType.ADMIN.name())).thenReturn(1L);

        assertThatThrownBy(() -> service.assignRoles(actorId, userId, Set.of(RoleType.USER)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.LAST_ADMIN_REMOVAL));
        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
    }

    @Test
    void assignRolesAllowsRemovingAdminWhenAnotherAdminExists() {
        UUID actorId = Uuidv7.generate();
        UUID userId = Uuidv7.generate();
        stubActor(actorId, RoleType.SUPER_ADMIN);
        User admin = userWithRoles("admin@b.com", RoleType.ADMIN);
        when(userRepository.findById(userId)).thenReturn(Optional.of(admin));
        when(roleRepository.findById(RoleType.USER.name()))
                .thenReturn(Optional.of(new Role(RoleType.USER.name(), "Regular user")));
        when(userRepository.countUsersWithRole(RoleType.ADMIN.name())).thenReturn(2L);

        service.assignRoles(actorId, userId, Set.of(RoleType.USER));

        assertThat(admin.getRoles()).extracting(Role::getName).containsExactly(RoleType.USER.name());
        verify(refreshTokenRepository).revokeAllForUser(eq(userId), any());
    }

    @Test
    void assignRolesRejectsRemovingLastSuperAdminViaSelfEdit() {
        UUID actorId = Uuidv7.generate();
        User self = userWithRoles("root@b.com", RoleType.SUPER_ADMIN);
        self.setId(actorId);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(self));
        when(roleRepository.findById(RoleType.USER.name()))
                .thenReturn(Optional.of(new Role(RoleType.USER.name(), "Regular user")));
        when(userRepository.countUsersWithRole(RoleType.SUPER_ADMIN.name())).thenReturn(1L);

        assertThatThrownBy(() -> service.assignRoles(actorId, actorId, Set.of(RoleType.USER)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthError.LAST_SUPER_ADMIN_REMOVAL));
        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
    }

    @Test
    void assignRolesAllowsSuperAdminToDropOwnRoleWhenAnotherExists() {
        UUID actorId = Uuidv7.generate();
        User self = userWithRoles("root@b.com", RoleType.SUPER_ADMIN);
        self.setId(actorId);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(self));
        when(roleRepository.findById(RoleType.USER.name()))
                .thenReturn(Optional.of(new Role(RoleType.USER.name(), "Regular user")));
        when(userRepository.countUsersWithRole(RoleType.SUPER_ADMIN.name())).thenReturn(2L);

        service.assignRoles(actorId, actorId, Set.of(RoleType.USER));

        assertThat(self.getRoles()).extracting(Role::getName).containsExactly(RoleType.USER.name());
        verify(refreshTokenRepository).revokeAllForUser(eq(actorId), any());
    }

    // ── listUsers ──

    @Test
    void listUsersReturnsPageWithNextCursorWhenMoreRows() {
        User first = userWithRoles("first@b.com", RoleType.USER);
        first.setId(Uuidv7.generate());
        first.setCreatedAt(Instant.now());
        User second = userWithRoles("second@b.com", RoleType.USER);
        second.setId(Uuidv7.generate());
        second.setCreatedAt(first.getCreatedAt().minusSeconds(1));

        // pageSize = 2, ambil 3 baris → hasMore = true
        when(userRepository.findAdminPage(any(), any(), any())).thenReturn(List.of(first, second, first));

        CursorPage<?> page = service.listUsers(null, 2, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    void listUsersWithoutCursorStartsFromNow() {
        when(userRepository.findAdminPage(any(), any(), any())).thenReturn(List.of());
        CursorPage<?> page = service.listUsers(null, 20, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }
}
