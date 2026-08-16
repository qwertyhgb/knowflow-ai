package io.github.qwertyhgb.knowflow.enterprise.dto.request;

import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改企业成员状态请求参数。
 *
 * <p>{@code status} 只接受 {@code NORMAL}（恢复成员）或 {@code DISABLED}（禁用/移除成员），
 * 直接使用 {@link EnterpriseMemberStatus} 枚举：非法字符串（如 {@code "FROZEN"}）会在
 * JSON 反序列化阶段被 Jackson 拒绝，返回 400；这里只需做非空校验。</p>
 */
@Getter
@Setter
public class EnterpriseMemberStatusUpdateRequest {

    /** 目标成员状态：NORMAL 恢复 / DISABLED 禁用。 */
    @NotNull(message = "成员状态不能为空")
    private EnterpriseMemberStatus status;
}
