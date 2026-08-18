package io.github.qwertyhgb.knowflow.ai.vo;

import io.github.qwertyhgb.knowflow.ai.entity.Conversation;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * AI 会话响应对象。
 *
 * <p>会话列表/创建接口的返回体，只暴露会话本身的信息，不包含消息（见
 * {@link ConversationDetailVO}）。时间字段使用 {@link Instant}，由 API 序列化层统一
 * 输出为 ISO-8601 UTC 字符串。</p>
 */
@Getter
public class ConversationVO {

    /** 会话 ID。 */
    private final Long id;

    /** 会话标题（创建时为「新对话」，首条消息后自动生成）。 */
    private final String title;

    /** 创建时间（UTC）。 */
    private final Instant createdAt;

    /** 更新时间（UTC）：会话列表按此倒序（最近活跃优先）。 */
    private final Instant updatedAt;

    private ConversationVO(Long id, String title, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 由会话实体构建安全的 API 响应对象。 */
    public static ConversationVO from(Conversation conversation) {
        return new ConversationVO(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
