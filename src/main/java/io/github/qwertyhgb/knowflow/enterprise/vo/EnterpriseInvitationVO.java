package io.github.qwertyhgb.knowflow.enterprise.vo;

import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseInvitation;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseInvitationStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import lombok.Getter;

import java.time.Instant;

/**
 * 企业成员邀请视图。
 *
 * <p><strong>关于 {@code token}（明文邀请令牌）：</strong>系统只保存令牌的
 * SHA-256 哈希，明文<strong>仅在创建邀请的响应中返回这一次</strong>，
 * 之后无法找回；令牌丢失只能由邀请人撤销后重新发起邀请。
 * 列表等后续接口组装 VO 时传 {@code null}，不返回令牌字段值。</p>
 */
@Getter
public class EnterpriseInvitationVO {

    private final Long id;

    private final Long enterpriseId;

    /** 企业名称，来自关联的 {@code enterprise} 表；创建/接受响应中为 null，仅列表组装时填充。 */
    private final String enterpriseName;

    /** 被邀请人邮箱（归一化小写），可能对应尚未注册的用户。 */
    private final String inviteeEmail;

    /** 接受邀请后授予的企业角色（ADMIN / MEMBER）。 */
    private final EnterpriseMemberRole role;

    private final EnterpriseInvitationStatus status;

    /** 邀请过期时间（UTC）。 */
    private final Instant expiresAt;

    private final Instant createdAt;

    /** 实际接受时间（UTC）；尚未接受时为 null。 */
    private final Instant acceptedAt;

    /**
     * 明文邀请令牌，仅创建邀请时返回一次；其余场景为 null。
     * 邀请人可通过任意渠道（邮件、IM）把它转交给被邀请人用于接受邀请。
     */
    private final String token;

    private EnterpriseInvitationVO(Long id, Long enterpriseId, String enterpriseName, String inviteeEmail,
                                   EnterpriseMemberRole role, EnterpriseInvitationStatus status,
                                   Instant expiresAt, Instant createdAt, Instant acceptedAt, String token) {
        this.id = id;
        this.enterpriseId = enterpriseId;
        this.enterpriseName = enterpriseName;
        this.inviteeEmail = inviteeEmail;
        this.role = role;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.acceptedAt = acceptedAt;
        this.token = token;
    }

    /**
     * 由邀请实体组装视图。
     *
     * @param invitation    邀请记录
     * @param token         明文邀请令牌；仅创建邀请的响应传入，其余场景传 null
     * @param enterpriseName 企业名称；被邀请人视角的列表（需要知道"是哪家企业"）传入，
     *                       企业视角的列表与创建/接受响应传 null
     * @return 邀请视图
     */
    public static EnterpriseInvitationVO from(EnterpriseInvitation invitation, String token, String enterpriseName) {
        return new EnterpriseInvitationVO(
                invitation.getId(),
                invitation.getEnterpriseId(),
                enterpriseName,
                invitation.getInviteeEmail(),
                invitation.getMemberRole(),
                invitation.getStatus(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt(),
                invitation.getAcceptedAt(),
                token);
    }
}
