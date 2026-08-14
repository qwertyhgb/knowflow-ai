package io.github.qwertyhgb.knowflow.user.controller;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.user.dto.request.UserChangePasswordRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserLoginRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserProfileUpdateRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserRegisterRequest;
import io.github.qwertyhgb.knowflow.user.entity.User;
import io.github.qwertyhgb.knowflow.user.enums.UserStatus;
import io.github.qwertyhgb.knowflow.user.service.UserService;
import io.github.qwertyhgb.knowflow.user.vo.UserLoginVO;
import io.github.qwertyhgb.knowflow.user.vo.UserVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TokenService tokenService;

    @Test
    void shouldRegisterUserAndReturnSafeMillisecondPrecisionResponse() throws Exception {
        when(userService.register(any(UserRegisterRequest.class))).thenReturn(userVO());

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "Bob@Example.COM",
                                  "password": "Password123!",
                                  "nickname": " Bob "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("bob@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("Bob"))
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-13T12:00:14.471Z"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-08-13T12:00:14.471Z"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void shouldRejectInvalidRegistrationRequest() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "invalid-email",
                                  "password": "short",
                                  "nickname": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(userService);
    }

    @Test
    void shouldReturnConflictWhenEmailAlreadyExists() throws Exception {
        when(userService.register(any(UserRegisterRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS));

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "registered@example.com",
                                  "password": "Password123!",
                                  "nickname": "用户"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("邮箱已被注册"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldLoginAndReturnTokenWithUser() throws Exception {
        when(userService.login(any(UserLoginRequest.class))).thenReturn(loginVO());

        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "Bob@Example.COM",
                                  "password": "Password123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.token").value("token-abc"))
                .andExpect(jsonPath("$.data.user.id").value(1))
                .andExpect(jsonPath("$.data.user.email").value("bob@example.com"))
                .andExpect(jsonPath("$.data.user.status").value("NORMAL"));
    }

    @Test
    void shouldReturnUnauthorizedForInvalidCredentials() throws Exception {
        when(userService.login(any(UserLoginRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "unknown@example.com",
                                  "password": "WrongPassword"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("邮箱或密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldRejectLoginWithBlankPassword() throws Exception {
        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "student@example.com",
                                  "password": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(userService);
    }

    @Test
    void shouldReturnCurrentUserWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(userService.getById(1L)).thenReturn(userVO());

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("bob@example.com"));
    }

    @Test
    void shouldReturnUnauthorizedWhenAccessingProtectedEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));
    }

    @Test
    void shouldUpdateCurrentUserProfile() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(userService.updateProfile(org.mockito.ArgumentMatchers.eq(1L),
                any(UserProfileUpdateRequest.class))).thenReturn(userVO("新昵称"));

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nickname": " 新昵称 "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.nickname").value("新昵称"));

        verify(userService).updateProfile(org.mockito.ArgumentMatchers.eq(1L),
                any(UserProfileUpdateRequest.class));
    }

    @Test
    void shouldRejectBlankNicknameWhenUpdatingProfile() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nickname": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verify(userService, never()).updateProfile(org.mockito.ArgumentMatchers.any(),
                any(UserProfileUpdateRequest.class));
    }

    @Test
    void shouldLogoutAndRevokeCurrentToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(post("/api/users/logout")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(userService).logout(1L, "valid-token");
    }

    @Test
    void shouldReturnUnauthorizedWhenLoggingOutWithoutToken() throws Exception {
        mockMvc.perform(post("/api/users/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(userService);
    }

    @Test
    void shouldReturnUnauthorizedWhenLoggingOutWithExpiredToken() throws Exception {
        when(tokenService.resolveUserId("expired-token")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/users/logout")
                        .header("Authorization", "Bearer expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(userService);
    }

    @Test
    void shouldChangePasswordWhenCurrentPasswordMatches() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Password123!",
                                  "newPassword": "NewPassword456!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(userService).changePassword(org.mockito.ArgumentMatchers.eq(1L),
                any(UserChangePasswordRequest.class));
    }

    @Test
    void shouldReturnBadRequestWhenCurrentPasswordIsWrong() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        doThrow(new BusinessException(ErrorCode.INVALID_PASSWORD))
                .when(userService).changePassword(any(), any());

        mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "WrongPassword",
                                  "newPassword": "NewPassword456!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"))
                .andExpect(jsonPath("$.message").value("当前密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldRejectChangePasswordWithBlankNewPassword() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Password123!",
                                  "newPassword": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verify(userService, never()).changePassword(any(), any());
    }

    @Test
    void shouldRejectChangePasswordWithTooLongCurrentPassword() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(put("/api/users/me/password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Password12345678901234567890!",
                                  "newPassword": "NewPassword456!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verify(userService, never()).changePassword(any(), any());
    }

    @Test
    void shouldDeserializeIso8601UtcTime() {
        TimePayload payload = jsonMapper.readValue(
                "{\"createdAt\":\"2026-08-13T12:00:14.471Z\"}",
                TimePayload.class);

        assertEquals(Instant.parse("2026-08-13T12:00:14.471Z"), payload.createdAt());
    }

    private UserVO userVO() {
        return userVO("Bob");
    }

    private UserVO userVO(String nickname) {
        Instant timeWithNanoseconds = Instant.parse("2026-08-13T12:00:14.471987654Z");
        User user = new User();
        user.setId(1L);
        user.setEmail("bob@example.com");
        user.setPasswordHash("must-not-be-returned");
        user.setNickname(nickname);
        user.setStatus(UserStatus.NORMAL);
        user.setCreatedAt(timeWithNanoseconds);
        user.setUpdatedAt(timeWithNanoseconds);
        return UserVO.from(user);
    }

    private UserLoginVO loginVO() {
        return UserLoginVO.of("token-abc", userVO());
    }

    private record TimePayload(Instant createdAt) {
    }
}
