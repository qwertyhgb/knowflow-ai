package io.github.qwertyhgb.knowflow.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseDepartmentStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 企业部门实体，对应表 {@code enterprise_department}。
 *
 * <p>部门通过 {@code parentId} 形成简单的父子层级；值为 {@code null} 时表示一级部门。
 * 数据库复合外键 {@code (enterprise_id, parent_id)} 保证父部门与子部门属于同一企业。</p>
 *
 * <p>字段依赖已开启的驼峰映射（{@code map-underscore-to-camel-case: true}）
 * 自动转换，例如 {@code enterprise_id} 映射为 {@code enterpriseId}。</p>
 */
@Getter
@Setter
@TableName("enterprise_department")
public class EnterpriseDepartment {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID，通过数据库外键关联 {@code enterprise.id}。 */
    private Long enterpriseId;

    /** 上级部门 ID；为 {@code null} 时表示一级部门。 */
    private Long parentId;

    /** 部门名称。 */
    private String name;

    /** 同级部门排序值，数值越小越靠前。 */
    private Integer sortOrder;

    private EnterpriseDepartmentStatus status;

    /** 创建时间（UTC）。 */
    private Instant createdAt;

    /** 更新时间（UTC）。 */
    private Instant updatedAt;
}
