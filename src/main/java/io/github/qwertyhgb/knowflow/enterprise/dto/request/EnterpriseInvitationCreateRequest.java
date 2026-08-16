package io.github.qwertyhgb.knowflow.enterprise.dto.request;

import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建企业成员邀请请求参数。
 *
 * <p>{@code email} 长度约束与 {@code sys_user.email}（VARCHAR(254)）保持一致：
 * 邀请允许发给尚未注册的邮箱，但对方将来注册后的邮箱必须与之一致
 * （归一化小写后比较）才能接受邀请。</p>
 *
 * <p>{@code role} 只能传 {@code MEMBER} 或 {@code ADMIN}：OWNER 是企业创建者的
 * 专属角色，不通过邀请授予（服务层会拒绝，返回 403）。非法的枚举字符串
 * （如 {@code "SUPERADMIN"}）会在 JSON 反序列化阶段被 Jackson 拒绝，返回 400。</p>
 */
@Getter
@Setter
public class EnterpriseInvitationCreateRequest {

    @NotBlank(message = "被邀请人邮箱不能为空")
    @Email(message = "被邀请人邮箱格式不正确")
    @Size(max = 254, message = "被邀请人邮箱长度不能超过254")
    private String email;

    /** 接受邀请后授予的企业角色，合法取值为 MEMBER、ADMIN。 */
    @NotNull(message = "邀请角色不能为空")
    private EnterpriseMemberRole role;
}
