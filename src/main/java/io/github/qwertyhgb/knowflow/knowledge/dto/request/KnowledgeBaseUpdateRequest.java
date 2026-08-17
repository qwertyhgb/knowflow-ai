package io.github.qwertyhgb.knowflow.knowledge.dto.request;

import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseAccessMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 更新知识库请求参数（PUT 全量语义）。
 *
 * <p>参照部门模块先例：全量更新必须同时提供全部可改字段。
 * {@code accessMode} 为 {@code @NotNull}——全量语义下客户端必须显式声明访问模式，
 * 不能沿用创建时的「未传默认 PRIVATE」逻辑，否则遗漏字段会被误当作默认值覆盖原配置。
 * 知识库名称在企业内不强制唯一，因此不做重名校验。</p>
 */
@Getter
@Setter
public class KnowledgeBaseUpdateRequest {

    /** 知识库名称，长度约束与 {@code knowledge_base.name} 保持一致。 */
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 100, message = "知识库名称长度不能超过100")
    private String name;

    /** 知识库描述，长度约束与 {@code knowledge_base.description} 保持一致。 */
    @Size(max = 500, message = "知识库描述长度不能超过500")
    private String description;

    /** 访问模式，全量更新下必须显式声明（PRIVATE / PUBLIC）。 */
    @NotNull(message = "访问模式不能为空")
    private KnowledgeBaseAccessMode accessMode;
}
