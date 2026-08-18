package io.github.qwertyhgb.knowflow.ai.vo;

import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * AI 会话详情响应对象。
 *
 * <p>会话详情 = 会话信息 + 该会话的全部消息（按创建时间升序）。前端据此渲染
 * 「会话标题 + 历史对话气泡」的完整对话页。</p>
 */
@Getter
public class ConversationDetailVO {

    /** 会话 ID。 */
    private final Long id;

    /** 会话标题。 */
    private final String title;

    /** 创建时间（UTC）。 */
    private final Instant createdAt;

    /** 更新时间（UTC）。 */
    private final Instant updatedAt;

    /** 会话内全部消息（按创建时间升序）。 */
    private final List<ConversationMessageVO> messages;

    private ConversationDetailVO(Long id, String title, Instant createdAt, Instant updatedAt,
                                 List<ConversationMessageVO> messages) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /**
     * 构造会话详情响应。
     *
     * @param id        会话 ID
     * @param title     会话标题
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param messages  会话内全部消息（调用方保证按创建时间升序）
     * @return 会话详情响应 VO
     */
    public static ConversationDetailVO of(Long id, String title, Instant createdAt, Instant updatedAt,
                                          List<ConversationMessageVO> messages) {
        return new ConversationDetailVO(id, title, createdAt, updatedAt, messages);
    }
}
