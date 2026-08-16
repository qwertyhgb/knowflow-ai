package io.github.qwertyhgb.knowflow.enterprise.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 接受企业成员邀请请求参数。
 *
 * <p>{@code token} 是创建邀请时一次性返回的明文令牌（32 位十六进制），
 * 属于凭证而非普通参数：只允许出现在请求体中，禁止放入 URL 路径
 * （URL 会进入访问日志与代理日志）。令牌长度下限 32 提前拦截明显无效的输入，
 * 上限 64 为未来令牌格式调整预留余量。</p>
 */
@Getter
@Setter
public class InvitationAcceptRequest {

    @NotBlank(message = "邀请令牌不能为空")
    @Size(min = 32, max = 64, message = "邀请令牌长度不正确")
    private String token;
}
