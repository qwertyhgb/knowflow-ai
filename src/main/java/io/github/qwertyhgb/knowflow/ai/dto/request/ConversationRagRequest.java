package io.github.qwertyhgb.knowflow.ai.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话内 RAG 对话请求。
 *
 * <p>入参：用户问题 + 检索参数（topK / scoreThreshold）。
 * topK 和 scoreThreshold 可为 null（Controller 调 resolve() 方法应用默认值）。</p>
 */
@Getter
@Setter
public class ConversationRagRequest {

    /**
     * 用户问题（必填，最大 1000 字符）。
     *
     * <p>【为什么限 1000 字符而不是 2000？】RAG 场景下用户问题主要是短问句
     * （「这份文档的缓存方案是什么」之类），不太可能写出 2000 字的长问题；
     * 限短一点既符合场景实际，也能降低 embedding 调用成本。</p>
     */
    @NotBlank(message = "问题不能为空")
    @Size(max = 1000, message = "问题不能超过 1000 字符")
    private String question;

    /**
     * 检索条数（1~20，可为 null）。
     *
     * <p>null 时由 Controller 应用默认值 5。topK 是向量检索的返回条数，
     * 过大会引入噪声、过小会遗漏关键信息，5 是 RAG 场景的经验中位值。</p>
     */
    @Min(value = 1, message = "topK 不能小于 1")
    @Max(value = 20, message = "topK 不能大于 20")
    private Integer topK;

    /**
     * 相似度阈值（0.0~1.0，可为 null）。
     *
     * <p>null 时由 Controller 应用默认值 0.3。阈值用于过滤低相关度噪声：
     * 检索结果 score 低于阈值的块被丢弃，避免误导模型。0.3 是教学场景的宽松默认值
     * （保留更多块，减少误杀）；生产可根据实际数据调优。</p>
     */
    @DecimalMin(value = "0.0", message = "scoreThreshold 不能小于 0.0")
    @DecimalMax(value = "1.0", message = "scoreThreshold 不能大于 1.0")
    private Double scoreThreshold;

    /**
     * 应用默认值：topK 默认 5，scoreThreshold 默认 0.3。
     *
     * <p>Controller 在调用 Service 前先调本方法，保证传给 Service 的参数非 null，
     * Service 层不再判 null（简化逻辑）。这是「可选参数 + 默认值」的标准处理模式。</p>
     *
     * @return this（链式调用）
     */
    public ConversationRagRequest resolve() {
        if (this.topK == null) {
            this.topK = 5;
        }
        if (this.scoreThreshold == null) {
            this.scoreThreshold = 0.3;
        }
        return this;
    }
}

