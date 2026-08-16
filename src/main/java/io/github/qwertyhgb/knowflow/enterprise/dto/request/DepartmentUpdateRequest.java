package io.github.qwertyhgb.knowflow.enterprise.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 更新企业部门请求参数。
 *
 * <p>PUT 按完整部门结构更新：{@code parentId} 为空表示移动为一级部门；
 * {@code sortOrder} 未传或显式传 {@code null} 时使用默认值 0。
 * 部门状态不在本接口中修改，应通过独立的状态接口启用或禁用部门。</p>
 */
@Getter
@Setter
public class DepartmentUpdateRequest {

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 100, message = "部门名称长度不能超过100")
    private String name;

    /** 新的上级部门 ID；为空表示移动为一级部门。 */
    @Positive(message = "上级部门ID必须为正数")
    private Long parentId;

    /** 新的同级排序值，数值越小越靠前。 */
    @Min(value = 0, message = "部门排序值不能小于0")
    private Integer sortOrder = 0;
}
