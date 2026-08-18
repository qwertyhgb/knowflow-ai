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
 * RAG 对话请求对象。
 *
 * <p>入参：问题（必填）+ 两个检索调优参数（均可空，走默认值）。</p>
 *
 * <p><strong>为什么 topK 与 scoreThreshold 要暴露在接口里？</strong>
 * Phase 11 的教学目标是理解 RAG 的检索质量如何受参数影响，所以把
 * 「最多取几个块」（Top K）与「最低多像才要」（阈值）两个旋钮都开放给调用方，
 * 前端可以做成调试面板，边调边看效果差异（详见手动验证的实验 1/2）。
 * 生产环境这类参数通常会收敛为服务端配置，不暴露给普通用户——本步为教学服务，
 * 刻意暴露。校验区间收紧：Top K 在 1~20，阈值在 0.0~1.0（相似度分数范围）。</p>
 */
@Getter
@Setter
public class RagChatRequest {

    /**
     * 用户问题（自然语言，与文档内容语义相关即可，无需逐词匹配）。
     *
     * <p>校验与语义搜索一致：{@code @NotBlank} 拦截空串/纯空白，
     * {@code @Size(max = 1000)} 限制长度（超长输入基本是误用，且 embedding 语义会发散）。</p>
     */
    @NotBlank(message = "问题不能为空")
    @Size(max = 1000, message = "问题长度不能超过1000")
    private String question;

    /**
     * 召回候选数（最多检索几个块），可空，默认 5。
     *
     * <p>Top K 是「最多取几个」的旋钮：太少召回不足（相关块被截断在外），
     * 太多噪声块混入（稀释答案 + 浪费 token）。详见 {@link SemanticSearchRequest} 类注释。</p>
     */
    @Min(value = 1, message = "topK 至少为 1")
    @Max(value = 20, message = "topK 最多为 20")
    private Integer topK;

    /**
     * 相似度阈值（低于此分数的块视为噪声丢弃），可空，默认 0.3。
     *
     * <p><strong>为什么叫「旋钮」？</strong>阈值是召回率/精确率的权衡：
     * 调太高——漏掉语义相关但分数略低的块（回答信息不足）；调太低——把不相关噪声
     * 也喂给模型（误导回答）。0.3 是 bge-m3 + ES 余弦归一化分数下的保守默认值，
     * 教学阶段建议实验 0.1~0.9 观察效果。</p>
     */
    @DecimalMin(value = "0.0", message = "scoreThreshold 不能小于 0")
    @DecimalMax(value = "1.0", message = "scoreThreshold 不能大于 1")
    private Double scoreThreshold;

    /** 未显式指定时的默认召回候选数（RAG 常用区间 5~10，取 5 保守优先）。 */
    private static final int DEFAULT_TOP_K = 5;

    /** 未显式指定时的默认相似度阈值。 */
    private static final double DEFAULT_SCORE_THRESHOLD = 0.3;

    /**
     * 取生效的 Top K 值：入参为空时回退默认值。
     *
     * <p>注意：不能用字段声明处 {@code private Integer topK = 5;} 做默认值——
     * 请求体显式传 {@code "topK": null} 时 Jackson 会把字段覆盖成 null，仍需判空回退。</p>
     *
     * @return 有效的 Top K 值
     */
    public int resolveTopK() {
        return topK == null ? DEFAULT_TOP_K : topK;
    }

    /**
     * 取生效的相似度阈值：入参为空时回退默认值（原因同上）。
     *
     * @return 有效的相似度阈值
     */
    public double resolveScoreThreshold() {
        return scoreThreshold == null ? DEFAULT_SCORE_THRESHOLD : scoreThreshold;
    }
}
