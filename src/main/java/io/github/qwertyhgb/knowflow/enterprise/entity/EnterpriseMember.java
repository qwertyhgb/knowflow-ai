package io.github.qwertyhgb.knowflow.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 企业成员关系实体，对应表 {@code enterprise_member}。
 *
 * <p>一名用户可以加入多个企业；同一用户在同一企业中只能有一条成员记录
 * （由唯一索引 {@code uk_enterprise_member_enterprise_user} 保证）。</p>
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

    /** 成员角色，默认 {@link EnterpriseMemberRole#MEMBER}。 */
    private EnterpriseMemberRole memberRole;

    private EnterpriseMemberStatus status;

    /** 加入企业的时间（UTC）。 */
    private Instant joinedAt;

    private Instant createdAt;

    private Instant updatedAt;
}
