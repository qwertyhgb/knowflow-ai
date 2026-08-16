package io.github.qwertyhgb.knowflow.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseInvitationStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 企业成员邀请实体，对应表 {@code enterprise_invitation}。
 *
 * <p>邀请按<strong>邮箱</strong>发出（{@code inviteeEmail}），被邀请人此时无需已注册；
 * 接受时再校验登录账号邮箱与邀请邮箱一致，并把实际接受人记录到
 * {@code acceptedByUserId} / {@code acceptedAt}（接受前为 null）。</p>
 *
 * <p>同一企业对同一邮箱允许存在多条历史邀请记录，但「待接受（PENDING）
 * 且未过期」的邀请同时最多一条，由业务层在创建时校验
 * （见 {@code EnterpriseInvitationServiceImpl}，与 V4 脚本注释约定一致）。</p>
 */
@Getter
@Setter
@TableName("enterprise_invitation")
public class EnterpriseInvitation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 目标企业 ID，通过数据库外键关联 {@code enterprise.id}。 */
    private Long enterpriseId;

    /** 发起邀请的用户 ID，通过数据库外键关联 {@code sys_user.id}。 */
    private Long inviterUserId;

    /** 被邀请人邮箱（归一化小写），允许尚未注册的用户。 */
    private String inviteeEmail;

    /** 接受邀请后授予的企业角色，只能为 {@link EnterpriseMemberRole#ADMIN} 或 {@link EnterpriseMemberRole#MEMBER}。 */
    private EnterpriseMemberRole memberRole;

    /**
     * 邀请令牌的 SHA-256 哈希（64 位十六进制字符），对应列 {@code token_hash CHAR(64)}。
     *
     * <p>与密码不存明文同理：<strong>数据库只存哈希，不存可直接使用的明文令牌</strong>，
     * 数据库泄露也不会泄露可用的邀请；明文令牌仅在创建邀请的响应中返回一次。</p>
     */
    private String tokenHash;

    /** 邀请状态，见 {@link EnterpriseInvitationStatus}。 */
    private EnterpriseInvitationStatus status;

    /** 邀请过期时间（UTC）；到期仍未接受即视为 EXPIRED。 */
    private Instant expiresAt;

    /** 实际接受邀请的用户 ID；接受前为 null。 */
    private Long acceptedByUserId;

    /** 接受邀请的时间（UTC）；接受前为 null。 */
    private Instant acceptedAt;

    private Instant createdAt;

    private Instant updatedAt;
}
