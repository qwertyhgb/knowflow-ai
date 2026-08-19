package io.github.qwertyhgb.knowflow.ticket.vo;

import io.github.qwertyhgb.knowflow.ticket.entity.TicketReply;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketReplyRole;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * 工单回复响应对象:工单详情中单条回复的返回体。
 *
 * <p><strong>citationsJson 字段的用途与取舍</strong>(与
 * {@code ConversationMessageVO.citationsJson} 同一决策):AI 回答的引用来源
 * 落库为 JSON 字符串({@code ticket_reply.citations}),这里原样透传,
 * 前端 {@code JSON.parse} 后渲染引用卡片。不反序列化为
 * {@code List<RagCitationVO>} 数组的原因:{@code RagCitationVO} 是不可变对象,
 * 本项目(Spring Boot 4 + Jackson 3)未把反序列化注解模块暴露到编译 classpath,
 * 强行反序列化需引入额外依赖——保持 JSON 字符串往返可靠且零新依赖。</p>
 */
@Getter
public class TicketReplyVO {

    /** 回复 ID。 */
    private final Long id;

    /** 回复角色:USER(用户)/ AI(AI 助手)/ SUPPORT(客服)。 */
    private final TicketReplyRole role;

    /** 回复人用户 ID(AI 回复无用户身份,为 null)。 */
    private final Long senderId;

    /** 回复内容。 */
    private final String content;

    /**
     * 引用来源 JSON 字符串(仅 AI 回复且有知识库依据时有值,转人工提示为 null)。
     *
     * <p>结构为 {@code List<RagCitationVO>} 的 JSON 序列化结果,前端
     * {@code JSON.parse} 后可复用引用卡片渲染逻辑。null 表示本回复无引用。</p>
     */
    private final String citationsJson;

    /** 创建时间(UTC),工单详情按此升序排列。 */
    private final Instant createdAt;

    private TicketReplyVO(Long id, TicketReplyRole role, Long senderId, String content,
                          String citationsJson, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.senderId = senderId;
        this.content = content;
        this.citationsJson = citationsJson;
        this.createdAt = createdAt;
    }

    /** 由回复实体构建安全的 API 响应对象(citations JSON 原样透传)。 */
    public static TicketReplyVO from(TicketReply reply) {
        return new TicketReplyVO(
                reply.getId(),
                reply.getRole(),
                reply.getSenderId(),
                reply.getContent(),
                reply.getCitations(),
                reply.getCreatedAt());
    }
}
