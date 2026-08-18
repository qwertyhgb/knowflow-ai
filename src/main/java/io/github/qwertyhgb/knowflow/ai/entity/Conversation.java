package io.github.qwertyhgb.knowflow.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * AI 会话实体，对应表 {@code ai_conversation}。
 *
 * <p>会话是消息的容器：一个用户可有多个会话，一个会话包含多条消息
 * （见 {@link AiMessage}）。会话是<strong>用户级</strong>资源——不绑定企业，
 * 不依赖 X-Enterprise-Id，与 AI 对话接口的定位一致（「个人 AI 助手」形态）。</p>
 *
 * <p>{@link #title} 创建时为默认值「新对话」，第一条用户消息到达后自动更新为
 * 首条消息前 20 字符（标题生成策略见 ConversationChatServiceImpl）。
 * {@link #updatedAt} 在发消息时更新，会话列表按它倒序（最近活跃优先）。</p>
 *
 * <p>字段依赖已开启的驼峰映射自动转换，例如 {@code user_id} 映射为 {@code userId}。</p>
 */
@Getter
@Setter
@TableName("ai_conversation")
public class Conversation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID（会话是用户级资源），归属校验的判据。 */
    private Long userId;

    /** 会话标题：创建时为默认值「新对话」，第一条消息后自动生成。 */
    private String title;

    /** 创建时间（UTC）。 */
    private Instant createdAt;

    /** 更新时间（UTC）：发消息时更新，列表按此倒序。 */
    private Instant updatedAt;
}
