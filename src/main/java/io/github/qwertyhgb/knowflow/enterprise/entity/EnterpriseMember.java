package io.github.qwertyhgb.knowflow.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 企业成员关系实体，对应表 {@code enterprise_member}。
 *
 * <p>一名用户可以加入多个企业；同一用户在同一企业中只能有一条成员记录
 * （由唯一索引 {@code uk_enterprise_member_enterprise_user} 保证）。</p>
 *
 * <p><strong>角色关联：</strong>成员角色经 {@link #roleId} 关联 {@code enterprise_role}，
 * 角色编码（OWNER / ADMIN / MEMBER）以 {@link EnterpriseRole#getCode()} 为准；
 * V3 遗留的 {@code member_role} 列已在 V7 迁移中退役删除。</p>
 */
@Getter
@Setter
@TableName("enterprise_member")
public class EnterpriseMember {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID，通过数据库外键关联 {@code enterprise.id}。 */
    private Long enterpriseId;

    /** 用户 ID，通过数据库外键关联 {@code sys_user.id}。 */
    private Long userId;

    /**
     * 成员关联的企业角色 ID，通过数据库外键关联 {@code enterprise_role.id}。
     *
     * <p>角色的编码与权限以 {@code enterprise_role} 为准（V6 起主用关联，
     * V7 已删除 V3 遗留的 {@code member_role} 字符串列）。</p>
     */
    private Long roleId;

    private EnterpriseMemberStatus status;

    /** 加入企业的时间（UTC）。 */
    private Instant joinedAt;

    private Instant createdAt;

    private Instant updatedAt;
}
