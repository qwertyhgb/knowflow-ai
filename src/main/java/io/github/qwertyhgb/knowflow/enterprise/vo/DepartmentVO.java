package io.github.qwertyhgb.knowflow.enterprise.vo;

import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseDepartment;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseDepartmentStatus;
import lombok.Getter;

import java.time.Instant;

/**
 * 企业部门响应对象。
 *
 * <p>只暴露部门接口需要的字段，避免把 {@link EnterpriseDepartment} 持久化实体
 * 直接返回给客户端。时间字段使用 {@link Instant}，由 API 序列化层统一输出为
 * ISO-8601 UTC 字符串。</p>
 */
@Getter
public class DepartmentVO {

    private final Long id;

    private final Long enterpriseId;

    private final Long parentId;

    private final String name;

    private final Integer sortOrder;

    private final EnterpriseDepartmentStatus status;

    private final Instant createdAt;

    private DepartmentVO(Long id, Long enterpriseId, Long parentId, String name,
                         Integer sortOrder, EnterpriseDepartmentStatus status, Instant createdAt) {
        this.id = id;
        this.enterpriseId = enterpriseId;
        this.parentId = parentId;
        this.name = name;
        this.sortOrder = sortOrder;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** 由部门实体构建安全的 API 响应对象。 */
    public static DepartmentVO from(EnterpriseDepartment department) {
        return new DepartmentVO(
                department.getId(),
                department.getEnterpriseId(),
                department.getParentId(),
                department.getName(),
                department.getSortOrder(),
                department.getStatus(),
                department.getCreatedAt());
    }
}
