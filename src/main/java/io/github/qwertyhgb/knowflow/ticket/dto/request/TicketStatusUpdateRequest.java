package io.github.qwertyhgb.knowflow.ticket.dto.request;

import io.github.qwertyhgb.knowflow.ticket.enums.TicketStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 工单状态流转请求对象。
 *
 * <p><strong>为什么只接受 PROCESSING / RESOLVED / CLOSED 三个目标状态?</strong>
 * OPEN 与 ASSIGNED 不是「设置」出来的,而是由具体业务动作驱动:</p>
 * <ul>
 *   <li>OPEN:创建工单时的初始状态;</li>
 *   <li>ASSIGNED:分配动作(assign)的副作用——同时设置 assignee_id,
 *       直接「设置状态」会绕过这个副作用,产生没有处理人的 ASSIGNED 工单;</li>
 *   <li>PROCESSING 也主要由客服回复自动推进,这里允许手动设置用于
 *       「已分配但尚未回复就开工」的场景。</li>
 * </ul>
 * <p>传入 OPEN / ASSIGNED 由 Service 层拒绝(INVALID_PARAMETER,400)。</p>
 */
@Getter
@Setter
public class TicketStatusUpdateRequest {

    /** 目标状态:仅 PROCESSING / RESOLVED / CLOSED,OPEN/ASSIGNED 由动作驱动不允许直接设置。 */
    @NotNull(message = "目标状态不能为空")
    private TicketStatus status;
}
