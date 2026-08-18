package io.github.qwertyhgb.knowflow.ai.vo;

import io.github.qwertyhgb.knowflow.ai.entity.AiMessage;
import io.github.qwertyhgb.knowflow.ai.enums.AiMessageRole;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * AI 会话消息响应对象。
 *
 * <p>会话详情接口中单条消息的返回体，包含角色与内容，供前端渲染对话历史。
 * 不暴露 token 统计字段（那是成本观察数据，不属于对话展示语义）。</p>
 *
 * <p><strong>citationsJson 字段的用途</strong>：RAG 对话时助手回答带引用来源，
 * 落库存储在 {@code ai_message.citations}（JSON 字符串）。会话详情返回本 VO 时，
 * 把该 JSON 原样透传，前端据此在重开会话时回溯引用（这不是新对话的实时引用，
 * 而是历史消息自带的引用）。</p>
 *
 * <p><strong>为什么透传 JSON 字符串而不是反序列化成 List&lt;RagCitationVO&gt; 数组？</strong>
 * {@code RagCitationVO} 是<strong>不可变对象</strong>（final 字段 + 私有构造 + 无 setter），
 * 且本项目（Spring Boot 4 + Jackson 3 / tools.jackson）未把专用于反序列化的注解模块
 * {@code tools.jackson.annotation} 暴露到编译 classpath——无法为私有构造标注
 * {@code @JsonCreator}，Jackson 也无法自动装配这种不可变类。强行反序列化需要引入
 * 额外依赖，违反"不引入新依赖"的边界。最稳妥的做法是保持 JSON 字符串原样下发，
 * 由前端 {@code JSON.parse} 还原为数组——往返可靠、零额外依赖，完全满足"引用可回溯"。</p>
 */
@Getter
public class ConversationMessageVO {

    /** 消息 ID。 */
    private final Long id;

    /** 消息角色：USER（用户）/ ASSISTANT（助手）。 */
    private final AiMessageRole role;

    /** 消息内容。 */
    private final String content;

    /** 创建时间（UTC），会话详情按此升序排列。 */
    private final Instant createdAt;

    /**
     * 引用来源 JSON 字符串（仅 RAG 对话的助手消息有值，普通对话/用户消息为 null）。
     *
     * <p>结构为 {@code List<RagCitationVO>} 的 JSON 序列化结果；前端 {@code JSON.parse}
     * 后可复用引用卡片渲染逻辑。null 表示本消息无引用。</p>
     */
    private final String citationsJson;

    private ConversationMessageVO(Long id, AiMessageRole role, String content, Instant createdAt,
                                  String citationsJson) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = content;
        this.createdAt = createdAt;
        this.citationsJson = citationsJson;
    }

    /**
     * 由消息实体构建安全的 API 响应对象。
     *
     * <p>把实体上的 citations 列（JSON 字符串）原样透传，普通对话/用户消息为 null。</p>
     */
    public static ConversationMessageVO from(AiMessage message) {
        return new ConversationMessageVO(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt(),
                message.getCitations());
    }
}
