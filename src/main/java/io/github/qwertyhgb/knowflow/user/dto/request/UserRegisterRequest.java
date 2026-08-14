package io.github.qwertyhgb.knowflow.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户注册请求参数。
 *
 * <p>字段长度约束与 {@code sys_user} 表保持一致：
 * {@code email} 为 VARCHAR(254)、{@code nickname} 为 VARCHAR(50)；
 * {@code password} 为明文密码，仅做注册入参，入库前统一转为哈希。</p>
 */
@Getter
@Setter
public class UserRegisterRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "邮箱长度不能超过254")
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度需在8到20位之间")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称长度不能超过50")
    private String nickname;
}
