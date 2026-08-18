package io.github.qwertyhgb.knowflow.ai.vo;

import lombok.Getter;

import java.util.Objects;

/**
 * AI 会话内对话响应对象。
 *
 * <p>会话内多轮对话的返回体：AI 回答 + 本次调用的 token 统计（成本观察）。
 * token 可能为 null——模型未返回 usage 时（不同模型能力不一），不阻塞主流程。</p>
 */
@Getter
public class ConversationChatVO {

    /** AI 回答文本。 */
    private final String reply;

    /** 本次调用的输入 token 数（LLM usage），缺失为 null。 */
    private final Integer inputTokens;

    /** 本次调用的输出 token 数（LLM usage），缺失为 null。 */
    private final Integer outputTokens;

    private ConversationChatVO(String reply, Integer inputTokens, Integer outputTokens) {
        this.reply = Objects.requireNonNull(reply, "reply must not be null");
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    /**
     * 构造会话对话响应。
     *
     * @param reply        AI 回答
     * @param inputTokens  输入 token 数（可为 null）
     * @param outputTokens 输出 token 数（可为 null）
     * @return 会话对话响应 VO
     */
    public static ConversationChatVO of(String reply, Integer inputTokens, Integer outputTokens) {
        return new ConversationChatVO(reply, inputTokens, outputTokens);
    }
}
