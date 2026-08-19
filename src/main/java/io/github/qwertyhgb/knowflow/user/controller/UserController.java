package io.github.qwertyhgb.knowflow.user.controller;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.auth.token.BearerTokenExtractor;
import io.github.qwertyhgb.knowflow.audit.annotation.OperationLog;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.user.dto.request.UserChangePasswordRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserLoginRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserProfileUpdateRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserRegisterRequest;
import io.github.qwertyhgb.knowflow.user.service.UserService;
import io.github.qwertyhgb.knowflow.user.vo.UserLoginVO;
import io.github.qwertyhgb.knowflow.user.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户注册。
     */
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody UserRegisterRequest request) {
        return Result.success(userService.register(request));
    }

    /**
     * 用户登录。
     *
     * <p>登录是最高频的审计点：成功失败都记录。登录接口未认证（无登录态），
     * 审计记录的 userId 为 null，表示「未登录场景的操作」。</p>
     */
    @PostMapping("/login")
    @OperationLog("用户登录")
    public Result<UserLoginVO> login(@Valid @RequestBody UserLoginRequest request) {
        return Result.success(userService.login(request));
    }

    /**
     * 获取当前登录用户信息。
     */
    @GetMapping("/me")
    public Result<UserVO> me(Authentication authentication) {
        // 认证主体为 EnterpriseUser（Token 认证建立）；/api/users/** 不属于企业作用域，企业上下文字段为 null。
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(userService.getById(userId));
    }

    /**
     * 修改当前登录用户资料。
     */
    @PatchMapping("/me")
    public Result<UserVO> updateProfile(Authentication authentication,
                                        @Valid @RequestBody UserProfileUpdateRequest request) {
        // 认证主体为 EnterpriseUser（Token 认证建立）；/api/users/** 不属于企业作用域，企业上下文字段为 null。
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(userService.updateProfile(userId, request));
    }

    /**
     * 修改当前登录用户密码。
     */
    @PutMapping("/me/password")
    @OperationLog("修改密码")
    public Result<Void> changePassword(Authentication authentication,
                                       @Valid @RequestBody UserChangePasswordRequest request) {
        // 认证主体为 EnterpriseUser（Token 认证建立）；/api/users/** 不属于企业作用域，企业上下文字段为 null。
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        userService.changePassword(userId, request);
        return Result.success();
    }

    /**
     * 用户登出：使当前 Token 失效。
     */
    @PostMapping("/logout")
    public Result<Void> logout(Authentication authentication, HttpServletRequest request) {
        // 认证主体为 EnterpriseUser（Token 认证建立）；/api/users/** 不属于企业作用域，企业上下文字段为 null。
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        userService.logout(userId, BearerTokenExtractor.extract(request));
        return Result.success();
    }
}
