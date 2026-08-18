package io.github.qwertyhgb.knowflow.ai.vo;

import lombok.Getter;

import java.util.Objects;

/**
 * AI 对话响应对象。
 *
 * <p>承载模型生成的回答。遵循项目 VO 规范：VO 只用于 Controller → 客户端，
 * 不用于数据库持久化；通过静态工厂 {@link #of} 构造对象，隐藏构造器并强制
 * 所有调用方走统一入口。</p>
 */
@Getter
public class AiChatResponse {

    /** 模型生成的回答文本。 */
    private final String reply;

    private AiChatResponse(String reply) {
        this.reply = Objects.requireNonNull(reply, "reply must not be null");
    }

    /**
     * 由回答文本构造响应 VO。
     *
     * @param reply 模型生成的回答
     * @return 对话响应 VO
     */
    public static AiChatResponse of(String reply) {
        return new AiChatResponse(reply);
    }
}
