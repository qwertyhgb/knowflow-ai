package io.github.qwertyhgb.knowflow.ai.vo;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * RAG 对话响应对象。
 *
 * <p>包含两部分：<strong>reply</strong>——模型基于检索上下文生成的回答
 * （引用处用 [1][2] 标注）；<strong>citations</strong>——与引用标注对应的
 * 来源资料块数组（前端可据此展示「查看原文」）。</p>
 */
@Getter
public class RagChatVO {

    /** 模型生成的回答文本（含 [序号] 引用标注）。 */
    private final String reply;

    /** 引用来源数组，顺序与回答中的 [序号] 一一对应。 */
    private final List<RagCitationVO> citations;

    private RagChatVO(String reply, List<RagCitationVO> citations) {
        this.reply = Objects.requireNonNull(reply, "reply must not be null");
        this.citations = Objects.requireNonNull(citations, "citations must not be null");
    }

    /**
     * 构造 RAG 对话响应。
     *
     * @param reply     模型回答（含引用标注）
     * @param citations 引用来源数组（与回答中的 [序号] 对应）
     * @return RAG 对话响应 VO
     */
    public static RagChatVO of(String reply, List<RagCitationVO> citations) {
        return new RagChatVO(reply, citations);
    }
}
