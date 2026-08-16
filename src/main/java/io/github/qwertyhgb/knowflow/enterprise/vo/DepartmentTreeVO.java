package io.github.qwertyhgb.knowflow.enterprise.vo;

import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseDepartment;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseDepartmentStatus;
import lombok.Getter;

import java.util.List;

/**
 * 企业部门树节点响应对象。
 *
 * <p>每个节点只包含部门展示与层级所需字段；叶子节点的 {@code children}
 * 固定为空数组，不返回 {@code null}，方便前端递归渲染。</p>
 */
@Getter
public class DepartmentTreeVO {

    private final Long id;

    private final Long enterpriseId;

    private final Long parentId;

    private final String name;

    private final Integer sortOrder;

    private final EnterpriseDepartmentStatus status;

    private final List<DepartmentTreeVO> children;

    private DepartmentTreeVO(Long id, Long enterpriseId, Long parentId, String name,
                             Integer sortOrder, EnterpriseDepartmentStatus status,
                             List<DepartmentTreeVO> children) {
        this.id = id;
        this.enterpriseId = enterpriseId;
        this.parentId = parentId;
        this.name = name;
        this.sortOrder = sortOrder;
        this.status = status;
        this.children = List.copyOf(children);
    }

    /** 由部门实体和已经组装好的子节点创建树节点。 */
    public static DepartmentTreeVO from(EnterpriseDepartment department,
                                        List<DepartmentTreeVO> children) {
        return new DepartmentTreeVO(
                department.getId(),
                department.getEnterpriseId(),
                department.getParentId(),
                department.getName(),
                department.getSortOrder(),
                department.getStatus(),
                children);
    }
}
