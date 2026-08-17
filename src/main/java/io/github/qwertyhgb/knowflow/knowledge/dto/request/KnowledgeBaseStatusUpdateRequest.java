package io.github.qwertyhgb.knowflow.knowledge.dto.request;

import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改知识库状态请求参数。
 *
 * <p>{@code status} 只接受 {@code NORMAL}（启用）或 {@code DISABLED}（禁用）。
 * 非法枚举字符串会在 JSON 反序列化阶段被拒绝；这里负责非空校验。</p>
 */
@Getter
@Setter
public class KnowledgeBaseStatusUpdateRequest {

    /** 目标知识库状态：NORMAL 启用 / DISABLED 禁用。 */
    @NotNull(message = "知识库状态不能为空")
    private KnowledgeBaseStatus status;
}
