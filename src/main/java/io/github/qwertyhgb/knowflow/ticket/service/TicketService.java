package io.github.qwertyhgb.knowflow.ticket.service;

import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketCreateRequest;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketReplyCreateRequest;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketDetailVO;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketVO;

import java.util.List;

/**
 * 工单服务接口(Phase 13 第一步:创建 + AI 自动回答 + 我的列表/详情)。
 *
 * <p>工单业务模型:客户提交工单 → AI 先用企业知识库自动回答(有依据则回答带引用)
 * → 无依据时 Human Handoff 转人工 → 客服分配处理 → 状态单向流转
 * (OPEN→ASSIGNED→PROCESSING→RESOLVED→CLOSED)。本步只覆盖前三段中的
 * 「创建 + AI 自动回答/转人工提示 + 列表/详情」,分配与状态流转是后续步骤。</p>
 */
public interface TicketService {

    /**
     * 创建工单并触发 AI 自动回答。
     *
     * <p>流程:落库工单(status=OPEN)→ 用工单描述在企业知识库检索 →
     * 有相关内容则调 LLM 生成带引用的回答;无相关内容(Human Handoff)或
     * AI 不可用/失败(降级)则落一条转人工提示回复。三种情况工单都创建成功。</p>
     *
     * @param enterpriseId 当前企业 ID(企业上下文,来自路径 + X-Enterprise-Id 校验后)
     * @param userId       提交人用户 ID(来自认证主体)
     * @param request      创建请求(title/description/category/priority)
     * @return 工单详情(工单 + 已生成的 AI 回复)
     */
    TicketDetailVO createTicket(Long enterpriseId, Long userId, TicketCreateRequest request);

    /**
     * 我的工单列表:当前用户在该企业提交的全部工单,按创建时间倒序。
     *
     * <p>本步是「我的工单」视角(客户看自己提交的);客服视角的「全部工单」
     * 需要权限与分配状态过滤,是后续步骤。</p>
     *
     * @param enterpriseId 当前企业 ID
     * @param userId       当前用户 ID
     * @return 工单列表(按创建时间倒序)
     */
    List<TicketVO> listMyTickets(Long enterpriseId, Long userId);

    /**
     * 工单详情(工单 + 全部回复,按创建时间升序)。
     *
     * <p>可见范围:提交人 或 客服(被分配处理人 / 企业 OWNER/ADMIN)——
     * 协作处理阶段客服必须能看详情才能接手。其他成员与不存在/跨企业工单
     * 统一抛 {@code TICKET_NOT_FOUND}(404),不泄露工单存在性。</p>
     *
     * @param enterpriseId 当前企业 ID
     * @param userId       当前用户 ID
     * @param roleCode     当前用户在企业内的角色编码(来自认证主体,判定管理兜底可见性)
     * @param ticketId     工单 ID
     * @return 工单详情(工单 + 回复历史)
     */
    TicketDetailVO getTicketDetail(Long enterpriseId, Long userId, String roleCode, Long ticketId);

    /**
     * 分配工单:管理员把工单分配给某企业成员(客服),OPEN → ASSIGNED。
     *
     * <p>仅企业 OWNER/ADMIN 可操作(分配是管理动作);工单必须是 OPEN 状态;
     * 目标客服必须是该企业正常成员(否则 400)。</p>
     *
     * @param enterpriseId     当前企业 ID
     * @param operatorUserId   操作者用户 ID
     * @param operatorRoleCode 操作者在当前企业的角色编码(OWNER/ADMIN/MEMBER,来自认证主体)
     * @param ticketId         工单 ID
     * @param assigneeId       被分配的客服用户 ID
     * @return 分配后的工单(assignee 已设置、状态 ASSIGNED)
     */
    TicketVO assignTicket(Long enterpriseId, Long operatorUserId, String operatorRoleCode,
                          Long ticketId, Long assigneeId);

    /**
     * 工单回复:提交人补充说明(USER)或客服处理回复(SUPPORT)。
     *
     * <p>回复角色由服务端按身份判定:提交人(且非被分配客服)回复为 USER;
     * 被分配客服或企业 OWNER/ADMIN 回复为 SUPPORT。SUPPORT 回复若工单处于
     * OPEN/ASSIGNED,状态自动推进为 PROCESSING(客服开始处理即视为处理中);
     * USER 回复不改状态。</p>
     *
     * @param enterpriseId 当前企业 ID
     * @param userId       回复人用户 ID
     * @param roleCode     回复人在当前企业的角色编码(来自认证主体)
     * @param ticketId     工单 ID
     * @param request      回复请求(content)
     * @return 工单详情(工单 + 全部回复升序)
     */
    TicketDetailVO addReply(Long enterpriseId, Long userId, String roleCode,
                            Long ticketId, TicketReplyCreateRequest request);

    /**
     * 工单状态流转:按状态机校验并推进(仅 PROCESSING/RESOLVED/CLOSED 可作为目标)。
     *
     * <p>合法流转:ASSIGNED → PROCESSING(客服)、PROCESSING → RESOLVED(客服)、
     * PROCESSING → CLOSED(客服)、RESOLVED → CLOSED(提交人确认或客服);
     * 其余一律拒绝(409 状态冲突)。</p>
     *
     * @param enterpriseId 当前企业 ID
     * @param userId       操作者用户 ID
     * @param roleCode     操作者在当前企业的角色编码(来自认证主体)
     * @param ticketId     工单 ID
     * @param request      状态流转请求(status)
     * @return 流转后的工单
     */
    TicketVO updateTicketStatus(Long enterpriseId, Long userId, String roleCode,
                                Long ticketId, TicketStatusUpdateRequest request);
}
