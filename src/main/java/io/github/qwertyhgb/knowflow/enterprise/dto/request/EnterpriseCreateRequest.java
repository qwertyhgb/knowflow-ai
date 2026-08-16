package io.github.qwertyhgb.knowflow.enterprise.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建企业请求参数。
 *
 * <p>字段长度约束与 {@code enterprise} 表保持一致：{@code name} 为 VARCHAR(100)。
 * 企业唯一标识 {@code slug} 由后端自动生成，不在此入参范围内。</p>
 */
@Getter
@Setter
public class EnterpriseCreateRequest {

    @NotBlank(message = "企业名称不能为空")
    @Size(max = 100, message = "企业名称长度不能超过100")
    private String name;
}
