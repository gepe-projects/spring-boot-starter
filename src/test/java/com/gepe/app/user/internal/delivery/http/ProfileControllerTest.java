package com.gepe.app.user.internal.delivery.http;

import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.exception.ServiceException;
import com.gepe.app.platform.support.Uuidv7;
import com.gepe.app.user.api.dto.UserProfileDto;
import com.gepe.app.user.internal.exception.UserProfileError;
import com.gepe.app.user.internal.service.ProfileServiceImpl;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProfileControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ProfileServiceImpl profileService;
    @MockitoBean
    MessageHelper messageHelper;

    @Test
    void updateProfileIsServedUnderVersionedUsersPath() throws Exception {
        UserProfileDto dto = new UserProfileDto(
                Uuidv7.generate(), "John Doe", "johndoe", "https://example.com/a.png",
                null, null, null, null, null, "Asia/Jakarta", "id",
                Instant.now(), Instant.now());
        when(messageHelper.get("user.profile_updated")).thenReturn("ok");
        when(profileService.update(any(), any())).thenReturn(dto);

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"John Doe\",\"nickname\":\"johndoe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("John Doe"))
                .andExpect(jsonPath("$.data.nickname").value("johndoe"));
    }

    @Test
    void invalidNicknamePatternIsBadRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"BAD UPPER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void takenNicknameIsConflict() throws Exception {
        when(messageHelper.get("user.nickname_taken")).thenReturn("taken");
        when(profileService.update(any(), any()))
                .thenThrow(new ServiceException(UserProfileError.NICKNAME_TAKEN));

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"taken\"}"))
                .andExpect(status().isConflict());
    }
}
