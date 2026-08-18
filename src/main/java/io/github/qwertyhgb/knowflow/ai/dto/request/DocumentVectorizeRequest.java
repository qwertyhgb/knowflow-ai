package io.github.qwertyhgb.knowflow.ai.dto.request;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档向量化请求对象。
 *
 * <p>接收客户端指定的文档 ID，触发该文档的「切块 → 向量化 → 落库 ES」全链路。</p>
 *
 * <p>【为什么用 body 传 documentId，而不是路径参数？】
 * 项目现有接口风格（知识库/文档接口）中，资源操作多用路径表达资源层级
 * （如 /api/enterprises/{enterpriseId}/knowledge-bases/...）；而本接口是
 * 一次「操作请求」（触发向量化任务），不涉及资源层级，语义上是
 * 「对某个文档做向量化操作」，用 body 传 ID 与现有请求对象风格一致
 * （如 AiChatRequest 也是 body 传参），且便于后续扩展更多可选参数
 * （如切块大小、是否强制重建等）。</p>
 */
@Getter
@Setter
public class DocumentVectorizeRequest {

    /**
     * 目标文档 ID，必须为正数。
     *
     * <p>{@code @Positive} 排除 0 与负数：0 或负数不可能对应真实文档，
     * 属于明显的参数错误，应在进入 Service 前被校验拦截（400），
     * 而不是让 Service 去数据库查一个必然不存在的 ID。</p>
     */
    @Positive(message = "documentId 必须为正数")
    private Long documentId;
}
