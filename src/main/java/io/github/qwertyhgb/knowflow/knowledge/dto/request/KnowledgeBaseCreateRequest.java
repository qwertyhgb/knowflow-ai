package io.github.qwertyhgb.knowflow.knowledge.dto.request;

import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseAccessMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建知识库请求参数。
 *
 * <p>{@code accessMode} 为可空字段：客户端不传时由服务层默认设置为
 * {@link KnowledgeBaseAccessMode#PRIVATE}（私有），不允许客户端在创建时
 * 决定企业级可见性之外的任何越权模式。知识库状态由后端统一初始化为 NORMAL。</p>
 *
 * <p>知识库名称在企业内不强制唯一（同名知识库用 ID 区分），因此不做重名校验。</p>
 */
@Getter
@Setter
public class KnowledgeBaseCreateRequest {

    /** 知识库名称，长度约束与 {@code knowledge_base.name} 保持一致。 */
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 100, message = "知识库名称长度不能超过100")
    private String name;

    /** 知识库描述，长度约束与 {@code knowledge_base.description} 保持一致。 */
    @Size(max = 500, message = "知识库描述长度不能超过500")
    private String description;

    /**
     * 访问模式：PRIVATE（私有，仅成员可见）/ PUBLIC（公开，企业内所有正常成员可见）。
     * 可空，未传时由服务层默认 {@code PRIVATE}。
     */
    private KnowledgeBaseAccessMode accessMode;
}
