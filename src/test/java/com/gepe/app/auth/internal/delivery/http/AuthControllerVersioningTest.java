package com.gepe.app.auth.internal.delivery.http;

import com.gepe.app.auth.api.dto.UserDto;
import com.gepe.app.auth.api.dto.UserStatus;
import com.gepe.app.auth.internal.dto.TokenResponse;
import com.gepe.app.auth.internal.service.AuthService;
import com.gepe.app.auth.internal.service.RefreshTokenService;
import com.gepe.app.auth.internal.service.SessionService;
import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.support.Uuidv7;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerVersioningTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthService authService;
    @MockitoBean
    SessionService sessionService;
    @MockitoBean
    RefreshTokenService refreshTokenService;
    @MockitoBean
    MessageHelper messageHelper;

    @Test
    void loginIsServedUnderVersionedPath() throws Exception {
        when(messageHelper.get("auth.login_success")).thenReturn("ok");
        when(authService.login(eq("a@b.com"), eq("password123"), any(), any()))
                .thenReturn(new TokenResponse("access-token", "refresh-token",
                        Uuidv7.generate(), Uuidv7.generate(),
                        new UserDto(Uuidv7.generate(), "a@b.com", false, UserStatus.ACTIVE, List.of("USER"))));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"));
    }

    @Test
    void unknownVersionIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v2/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"pw\"}"))
                .andExpect(status().isNotFound());
    }
}
