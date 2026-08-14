package io.github.qwertyhgb.knowflow.user.service;

import io.github.qwertyhgb.knowflow.user.dto.request.UserLoginRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserProfileUpdateRequest;
import io.github.qwertyhgb.knowflow.user.dto.request.UserRegisterRequest;
import io.github.qwertyhgb.knowflow.user.vo.UserLoginVO;
import io.github.qwertyhgb.knowflow.user.vo.UserVO;

/**
 * 用户业务服务。
 */
public interface UserService {

    /**
     * 注册新用户。
     *
     * @param request 注册请求参数
     * @return 注册成功后的用户视图
     */
    UserVO register(UserRegisterRequest request);

    /**
     * 用户登录。
     *
     * @param request 登录请求参数
     * @return 登录成功后的 Token 与用户视图
     */
    UserLoginVO login(UserLoginRequest request);

    /**
     * 按 ID 查询用户。
     *
     * @param id 用户 ID
     * @return 用户视图
     */
    UserVO getById(Long id);

    /**
     * 修改当前用户资料。
     *
     * @param userId  当前登录用户 ID
     * @param request 资料修改请求参数
     * @return 修改后的用户视图
     */
    UserVO updateProfile(Long userId, UserProfileUpdateRequest request);

    /**
     * 用户登出：使指定 Token 失效。
     *
     * @param userId 当前登录用户 ID
     * @param token  待失效的 Token
     */
    void logout(Long userId, String token);
}
