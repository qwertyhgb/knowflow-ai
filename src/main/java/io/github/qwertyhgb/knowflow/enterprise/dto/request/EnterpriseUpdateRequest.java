package io.github.qwertyhgb.knowflow.enterprise.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 更新企业请求参数。
 *
 * <p>当前仅支持修改企业名称，字段约束与 {@code enterprise.name} 列（VARCHAR(100)）一致。
 * 企业唯一标识 {@code slug} 创建后保持不变，不在更新范围内。</p>
 */
@Getter
@Setter
public class EnterpriseUpdateRequest {

    @NotBlank(message = "企业名称不能为空")
    @Size(max = 100, message = "企业名称长度不能超过100")
    private String name;
}
