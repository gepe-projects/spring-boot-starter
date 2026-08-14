package com.gepe.app.admin.internal.delivery.http;

import com.gepe.app.admin.internal.dto.AdminAuditLogDto;
import com.gepe.app.admin.internal.service.AdminAuditService;
import com.gepe.app.admin.internal.service.AdminKeyService;
import com.gepe.app.admin.internal.service.AdminUserService;
import com.gepe.app.auth.api.AdminUserDetailDto;
import com.gepe.app.auth.api.RotatedKeyResponse;
import com.gepe.app.auth.api.UserDto;
import com.gepe.app.auth.api.UserStatus;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.pagination.CursorPage;
import com.gepe.app.platform.support.Uuidv7;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AdminUserController.class, AdminKeyController.class, AdminAuditController.class})
@AutoConfigureMockMvc(addFilters = false)
class AdminControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AdminUserService adminUserService;
    @MockitoBean
    AdminKeyService adminKeyService;
    @MockitoBean
    AdminAuditService adminAuditService;
    @MockitoBean
    MessageHelper messageHelper;

    @Test
    void listUsersIsServedUnderAdminPath() throws Exception {
        UserDto user = new UserDto(Uuidv7.generate(), "a@b.com", false, UserStatus.ACTIVE, List.of("USER"));
        when(adminUserService.listUsers(eq(null), eq(20), eq(null)))
                .thenReturn(new CursorPage<>(List.of(user), null, false));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].email").value("a@b.com"));
    }

    @Test
    void updateStatusDelegatesWithActorId() throws Exception {
        UUID userId = Uuidv7.generate();
        when(messageHelper.get("admin.user_status_updated")).thenReturn("ok");

        mockMvc.perform(patch("/api/v1/admin/users/" + userId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        verify(adminUserService).changeStatus(any(), eq(userId), eq(UserStatus.SUSPENDED));
    }

    @Test
    void updateStatusRejectsInvalidBody() throws Exception {
        UUID userId = Uuidv7.generate();
        mockMvc.perform(patch("/api/v1/admin/users/" + userId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assignRolesDelegatesWithActorId() throws Exception {
        UUID userId = Uuidv7.generate();
        when(messageHelper.get("admin.roles_updated")).thenReturn("ok");

        mockMvc.perform(put("/api/v1/admin/users/" + userId + "/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"USER\",\"OPERATION\"]}"))
                .andExpect(status().isOk());

        verify(adminUserService).assignRoles(any(), eq(userId), any());
    }

    @Test
    void rotateSigningKeyServesUnderAdminKeysPath() throws Exception {
        RotatedKeyResponse response = new RotatedKeyResponse(Uuidv7.generate(), "ACTIVE", Instant.now());
        when(adminKeyService.rotateSigningKey(any())).thenReturn(response);
        when(messageHelper.get("admin.keys_rotated_success")).thenReturn("rotated");

        mockMvc.perform(post("/api/v1/admin/keys/rotate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void listKeysReturnsKeys() throws Exception {
        when(adminKeyService.listSigningKeys()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/admin/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getUserDetailReturnsDetail() throws Exception {
        UUID userId = Uuidv7.generate();
        AdminUserDetailDto detail = new AdminUserDetailDto(
                userId, "a@b.com", true, UserStatus.ACTIVE, Instant.now(), Instant.now(), Instant.now(),
                List.of("ADMIN"));
        when(adminUserService.getUser(userId)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/admin/users/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("a@b.com"))
                .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"));
    }

    @Test
    void listAuditLogsReturnsPage() throws Exception {
        AdminAuditLogDto log = new AdminAuditLogDto(
                Uuidv7.generate(), Uuidv7.generate(), "USER_STATUS_CHANGED", "USER",
                Uuidv7.generate().toString(), "{\"status\":\"SUSPENDED\"}", Instant.now());
        when(adminAuditService.list(any(), anyInt()))
                .thenReturn(new CursorPage<>(List.of(log), null, false));

        mockMvc.perform(get("/api/v1/admin/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].action").value("USER_STATUS_CHANGED"));
    }
}
