package io.github.qwertyhgb.knowflow.user.service.impl;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.user.dto.request.UserChangePasswordRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserLoginRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserProfileUpdateRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserRegisterRequest;
import io.github.qwertyhgb.knowflow.user.entity.User;
import io.github.qwertyhgb.knowflow.user.enums.UserStatus;
import io.github.qwertyhgb.knowflow.user.mapper.UserMapper;
import io.github.qwertyhgb.knowflow.user.vo.UserLoginVO;
import io.github.qwertyhgb.knowflow.user.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        userService = new UserServiceImpl(userMapper, passwordEncoder, clock, tokenService);
    }

    @Test
    void shouldNormalizeInputAndRegisterUser() {
        UserRegisterRequest request = request("  Student@Example.COM  ", "Password123!", "  小明  ");
        when(userMapper.exists(any())).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed-password");
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return 1;
        });

        UserVO result = userService.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("student@example.com", savedUser.getEmail());
        assertEquals("小明", savedUser.getNickname());
        assertEquals("hashed-password", savedUser.getPasswordHash());
        assertEquals(UserStatus.NORMAL, savedUser.getStatus());
        assertEquals(NOW, savedUser.getCreatedAt());
        assertEquals(NOW, savedUser.getUpdatedAt());
        assertEquals(1L, result.getId());
    }

    @Test
    void shouldRejectExistingEmailBeforeInsert() {
        UserRegisterRequest request = request("registered@example.com", "Password123!", "用户");
        when(userMapper.exists(any())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.register(request));

        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, exception.getErrorCode());
        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void shouldLoginSuccessfully() {
        UserLoginRequest request = loginRequest("  Student@Example.COM  ", "Password123!");
        User user = user(1L, "student@example.com", "hashed-password", UserStatus.NORMAL);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("Password123!", "hashed-password")).thenReturn(true);
        when(tokenService.createToken(1L)).thenReturn("token-abc");

        UserLoginVO result = userService.login(request);

        assertEquals("token-abc", result.getToken());
        assertEquals(1L, result.getUser().getId());
        assertEquals("student@example.com", result.getUser().getEmail());
        assertEquals(UserStatus.NORMAL, result.getUser().getStatus());
    }

    @Test
    void shouldRejectLoginWithWrongPassword() {
        UserLoginRequest request = loginRequest("student@example.com", "WrongPassword");
        User user = user(1L, "student@example.com", "hashed-password", UserStatus.NORMAL);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("WrongPassword", "hashed-password")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.login(request));

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void shouldRejectLoginWithUnknownEmail() {
        UserLoginRequest request = loginRequest("unknown@example.com", "Password123!");
        when(userMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.login(request));

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        verify(passwordEncoder, never()).matches(any(CharSequence.class), any(String.class));
    }

    @Test
    void shouldRejectLoginWhenUserDisabled() {
        UserLoginRequest request = loginRequest("disabled@example.com", "Password123!");
        User user = user(2L, "disabled@example.com", "hashed-password", UserStatus.DISABLED);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("Password123!", "hashed-password")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.login(request));

        assertEquals(ErrorCode.USER_DISABLED, exception.getErrorCode());
    }

    @Test
    void shouldRevokeCurrentTokenWhenLoggingOut() {
        userService.logout(1L, "valid-token");

        verify(tokenService).revokeToken("valid-token");
        verify(tokenService, never()).resolveUserId(any());
    }

    @Test
    void shouldNormalizeNicknameAndUpdateProfile() {
        User existingUser = user(1L, "student@example.com", "hashed-password", UserStatus.NORMAL);
        when(userMapper.selectById(1L)).thenReturn(existingUser);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UserVO result = userService.updateProfile(1L, profileRequest("  新昵称  "));

        ArgumentCaptor<User> updateCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(updateCaptor.capture());
        User update = updateCaptor.getValue();
        assertEquals(1L, update.getId());
        assertEquals("新昵称", update.getNickname());
        assertEquals(NOW, update.getUpdatedAt());
        assertNull(update.getEmail());
        assertNull(update.getPasswordHash());
        assertNull(update.getStatus());
        assertNull(update.getCreatedAt());
        assertEquals("新昵称", result.getNickname());
        assertEquals(NOW, result.getUpdatedAt());
    }

    @Test
    void shouldSkipProfileUpdateWhenNormalizedNicknameIsUnchanged() {
        User existingUser = user(1L, "student@example.com", "hashed-password", UserStatus.NORMAL);
        when(userMapper.selectById(1L)).thenReturn(existingUser);

        UserVO result = userService.updateProfile(1L, profileRequest("  用户  "));

        assertEquals("用户", result.getNickname());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void shouldRejectProfileUpdateWhenUserDoesNotExist() {
        when(userMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.updateProfile(99L, profileRequest("新昵称")));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void shouldHashAndPersistNewPasswordWhenCurrentPasswordMatches() {
        User existingUser = user(1L, "student@example.com", "hashed-password", UserStatus.NORMAL);
        when(userMapper.selectById(1L)).thenReturn(existingUser);
        when(passwordEncoder.matches("Password123!", "hashed-password")).thenReturn(true);
        when(passwordEncoder.encode("NewPassword456!")).thenReturn("new-hashed-password");
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        userService.changePassword(1L, changePasswordRequest("Password123!", "NewPassword456!"));

        ArgumentCaptor<User> updateCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(updateCaptor.capture());
        User update = updateCaptor.getValue();
        assertEquals(1L, update.getId());
        assertEquals("new-hashed-password", update.getPasswordHash());
        assertEquals(NOW, update.getUpdatedAt());
        assertNull(update.getEmail());
        assertNull(update.getNickname());
        assertNull(update.getStatus());
        assertNull(update.getCreatedAt());
    }

    @Test
    void shouldRejectChangePasswordWhenCurrentPasswordIsWrong() {
        User existingUser = user(1L, "student@example.com", "hashed-password", UserStatus.NORMAL);
        when(userMapper.selectById(1L)).thenReturn(existingUser);
        when(passwordEncoder.matches("WrongPassword", "hashed-password")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.changePassword(1L, changePasswordRequest("WrongPassword", "NewPassword456!")));

        assertEquals(ErrorCode.INVALID_PASSWORD, exception.getErrorCode());
        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void shouldRejectChangePasswordWhenUserDoesNotExist() {
        when(userMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.changePassword(99L, changePasswordRequest("Password123!", "NewPassword456!")));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(passwordEncoder, never()).matches(any(CharSequence.class), any(String.class));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void shouldSkipUpdateWhenNewPasswordEqualsCurrentPassword() {
        User existingUser = user(1L, "student@example.com", "hashed-password", UserStatus.NORMAL);
        when(userMapper.selectById(1L)).thenReturn(existingUser);
        when(passwordEncoder.matches("Password123!", "hashed-password")).thenReturn(true);

        userService.changePassword(1L, changePasswordRequest("Password123!", "Password123!"));

        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).updateById(any(User.class));
    }

    private UserRegisterRequest request(String email, String password, String nickname) {
        UserRegisterRequest request = new UserRegisterRequest();
        request.setEmail(email);
        request.setPassword(password);
        request.setNickname(nickname);
        return request;
    }

    private UserLoginRequest loginRequest(String email, String password) {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }

    private UserProfileUpdateRequest profileRequest(String nickname) {
        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        request.setNickname(nickname);
        return request;
    }

    private UserChangePasswordRequest changePasswordRequest(String currentPassword, String newPassword) {
        UserChangePasswordRequest request = new UserChangePasswordRequest();
        request.setCurrentPassword(currentPassword);
        request.setNewPassword(newPassword);
        return request;
    }

    private User user(Long id, String email, String passwordHash, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setNickname("用户");
        user.setStatus(status);
        user.setCreatedAt(NOW);
        user.setUpdatedAt(NOW);
        return user;
    }
}
