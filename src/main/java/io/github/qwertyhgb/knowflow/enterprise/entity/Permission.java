package io.github.qwertyhgb.knowflow.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 平台级权限实体，对应表 {@code permission}。
 *
 * <p>权限与租户无关，是全局的、可复用的能力清单（如 {@code member:remove}）；
 * 由 {@code enterprise_role_permission} 把权限授予具体企业的具体角色。
 * <strong>权限码是接口鉴权 {@code @PreAuthorize("hasAuthority('member:remove')")}
 * 的判断依据</strong>，因此一条权限码对应一个可授权的操作。</p>
 */
@Getter
@Setter
@TableName("permission")
public class Permission {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 权限编码，平台全局唯一，如 {@code enterprise:update}。 */
    private String code;

    /** 权限名称，面向展示。 */
    private String name;

    private Instant createdAt;

    private Instant updatedAt;
}
