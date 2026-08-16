package io.github.qwertyhgb.knowflow.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 企业角色实体，对应表 {@code enterprise_role}。
 *
 * <p>角色是企业级资源：归属于单个企业，同一企业内角色编码唯一
 * （唯一索引 {@code uk_enterprise_role_enterprise_code} 保证）。
 * 成员通过 {@code enterprise_member.role_id} 关联角色，
 * 角色再通过 {@code enterprise_role_permission} 关联平台级权限，
 * 从而形成「用户 → 成员 → 角色 → 权限」的 RBAC 链路。</p>
 */
@Getter
@Setter
@TableName("enterprise_role")
public class EnterpriseRole {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID，通过数据库外键关联 {@code enterprise.id}。 */
    private Long enterpriseId;

    /** 角色编码，企业内部唯一：OWNER、ADMIN、MEMBER 等。 */
    private String code;

    /** 角色名称，面向展示。 */
    private String name;

    /** 角色描述，说明角色职责，可空。 */
    private String description;

    /** 角色状态：{@link EnterpriseRoleStatus#NORMAL} 正常、{@link EnterpriseRoleStatus#DISABLED} 禁用。 */
    private EnterpriseRoleStatus status;

    private Instant createdAt;

    private Instant updatedAt;
}
