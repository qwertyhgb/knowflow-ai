package io.github.qwertyhgb.knowflow.ticket.vo;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * 工单详情响应对象。
 *
 * <p>工单详情 = 工单信息 + 该工单的全部回复(按创建时间升序)。前端据此渲染
 * 「工单信息卡片 + 多方对话流(AI 自动回答/用户补充/客服处理)」的完整详情页。
 * 创建工单接口也返回本对象——创建后立即能看到 AI 的自动回答(或转人工提示)。</p>
 */
@Getter
public class TicketDetailVO {

    /** 工单信息。 */
    private final TicketVO ticket;

    /** 该工单的全部回复(按创建时间升序,回放对话流)。 */
    private final List<TicketReplyVO> replies;

    private TicketDetailVO(TicketVO ticket, List<TicketReplyVO> replies) {
        this.ticket = Objects.requireNonNull(ticket, "ticket must not be null");
        this.replies = Objects.requireNonNull(replies, "replies must not be null");
    }

    /** 组装工单详情(工单信息 + 回复列表)。 */
    public static TicketDetailVO of(TicketVO ticket, List<TicketReplyVO> replies) {
        return new TicketDetailVO(ticket, replies);
    }
}
