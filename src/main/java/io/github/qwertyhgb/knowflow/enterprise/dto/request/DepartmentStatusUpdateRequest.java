package io.github.qwertyhgb.knowflow.enterprise.dto.request;

import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseDepartmentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改企业部门状态请求参数。
 *
 * <p>{@code status} 只接受 {@code NORMAL}（启用）或 {@code DISABLED}（禁用）。
 * 非法枚举字符串会在 JSON 反序列化阶段被拒绝；这里负责非空校验。</p>
 */
@Getter
@Setter
public class DepartmentStatusUpdateRequest {

    /** 目标部门状态：NORMAL 启用 / DISABLED 禁用。 */
    @NotNull(message = "部门状态不能为空")
    private EnterpriseDepartmentStatus status;
}
