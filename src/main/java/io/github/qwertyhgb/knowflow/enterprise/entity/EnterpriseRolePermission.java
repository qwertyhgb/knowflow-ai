package io.github.qwertyhgb.knowflow.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 企业角色-权限多对多关联实体，对应表 {@code enterprise_role_permission}。
 *
 * <p>一条记录表示「某个企业的某个角色」被授予「某个平台级权限」。
 * 同一角色对同一权限只记录一次（唯一索引
 * {@code uk_enterprise_role_permission_role_permission} 保证）。
 * 由于角色是企业级资源，角色权限组合天然带企业隔离，不会跨企业生效。</p>
 */
@Getter
@Setter
@TableName("enterprise_role_permission")
public class EnterpriseRolePermission {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 企业角色 ID，通过数据库外键关联 {@code enterprise_role.id}。 */
    private Long enterpriseRoleId;

    /** 权限 ID，通过数据库外键关联 {@code permission.id}。 */
    private Long permissionId;

    private Instant createdAt;
}
