package io.github.qwertyhgb.knowflow.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户登录请求参数。
 *
 * <p>学习阶段与注册保持相同的 8～20 位长度规则，让参数校验集中在 Request DTO，
 * Service 只关注查询用户、校验密码和签发 Token 的业务流程。</p>
 */
@Getter
@Setter
public class UserLoginRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "邮箱长度不能超过254")
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度需在8到20位之间")
    private String password;
}
