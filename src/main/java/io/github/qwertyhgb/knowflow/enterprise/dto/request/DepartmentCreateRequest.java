package io.github.qwertyhgb.knowflow.enterprise.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建企业部门请求参数。
 *
 * <p>{@code parentId} 为空时创建一级部门；非空时由服务层校验父部门属于当前企业。
 * 部门状态由后端统一初始化为 NORMAL，不允许客户端在创建时指定。</p>
 */
@Getter
@Setter
public class DepartmentCreateRequest {

    /** 部门名称，长度约束与 {@code enterprise_department.name} 保持一致。 */
    @NotBlank(message = "部门名称不能为空")
    @Size(max = 100, message = "部门名称长度不能超过100")
    private String name;

    /** 上级部门 ID；为空表示创建一级部门。 */
    @Positive(message = "上级部门ID必须为正数")
    private Long parentId;

    /** 同级排序值，数值越小越靠前；未传时默认为 0。 */
    @Min(value = 0, message = "部门排序值不能小于0")
    private Integer sortOrder = 0;
}
