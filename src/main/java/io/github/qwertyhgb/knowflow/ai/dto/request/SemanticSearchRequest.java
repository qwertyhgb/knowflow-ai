package io.github.qwertyhgb.knowflow.ai.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 语义搜索请求对象。
 *
 * <p>入参：问题文本（必填）+ 返回条数 Top K（可空，默认 5）。</p>
 *
 * <p>【为什么限制 topK 在 1~20？】
 * 这是检索调优的权衡：<strong>Top K 太少</strong>——召回不足，真正相关的块可能被
 * 截断在外，下游（Phase 11 的 RAG）没有足够的候选上下文可用；
 * <strong>Top K 太多</strong>——不相关的噪声块混进来，既拖慢响应（向量检索 + 后续
 * LLM 拼接的 token 成本都随 K 线性增长），又会稀释答案质量。
 * 5~10 是 RAG 应用常用的区间：默认 5 兼顾质量与成本，上限 20 防止恶意/误用
 * 一次拉取海量块。</p>
 */
@Getter
@Setter
public class SemanticSearchRequest {

    /**
     * 用户问题（自然语言，无需与文档用词一致——语义搜索按「意思」匹配）。
     *
     * <p>{@code @NotBlank} 拦截空串/纯空白；{@code @Size(max = 1000)} 限制长度：
     * 问题越长，embedding 时语义越分散、向量越不聚焦，且 1000 字符对客服问题
     * 绰绰有余，超长输入基本是误用。</p>
     */
    @NotBlank(message = "问题不能为空")
    @Size(max = 1000, message = "问题长度不能超过1000")
    private String question;

    /**
     * 返回的相似块数量，可空（不传则用 {@link #DEFAULT_TOP_K}）。
     *
     * <p>为什么可空而不强制？语义搜索的 K 值属于「检索调优参数」，前端通常有默认值；
     * 允许缺省时走默认值，保持接口友好。校验区间 1~20（见类注释）。</p>
     */
    @Min(value = 1, message = "topK 至少为 1")
    @Max(value = 20, message = "topK 最多为 20")
    private Integer topK;

    /** 未显式指定时的默认返回条数（RAG 常用区间 5~10，取 5 保守优先）。 */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 取 Top K 值：入参为空时回退默认值。
     *
     * <p>注意：不能在字段声明处用 {@code private Integer topK = 5;} 做默认值——
     * 那样当请求体显式传 {@code "topK": null} 时 Jackson 会把字段覆盖成 null，
     * 仍需判空回退；统一在这里集中处理更清晰。</p>
     *
     * @return 有效的 Top K 值
     */
    public int resolveTopK() {
        return topK == null ? DEFAULT_TOP_K : topK;
    }
}
