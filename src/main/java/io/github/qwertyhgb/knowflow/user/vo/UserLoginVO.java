package io.github.qwertyhgb.knowflow.user.vo;

import lombok.Getter;

/**
 * 登录响应对象：Token 与用户视图的组合。
 */
@Getter
public class UserLoginVO {

    private final String token;

    private final UserVO user;

    private UserLoginVO(String token, UserVO user) {
        this.token = token;
        this.user = user;
    }

    public static UserLoginVO of(String token, UserVO user) {
        return new UserLoginVO(token, user);
    }
}
