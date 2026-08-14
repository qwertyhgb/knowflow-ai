package io.github.qwertyhgb.knowflow.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 当前用户修改密码请求参数。
 *
 * <p>当前密码和新密码的长度规则都与注册/登录保持一致（8~20 位），避免校验口径漂移；
 * 明文密码仅做入参，Service 中转为哈希后入库。</p>
 */
@Getter
@Setter
public class UserChangePasswordRequest {

    @NotBlank(message = "当前密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度需在8到20位之间")
    private String currentPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度需在8到20位之间")
    private String newPassword;
}
