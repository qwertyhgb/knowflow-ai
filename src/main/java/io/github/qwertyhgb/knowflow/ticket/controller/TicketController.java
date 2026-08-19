package io.github.qwertyhgb.knowflow.ticket.controller;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketAssignRequest;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketCreateRequest;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketReplyCreateRequest;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.ticket.service.TicketService;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketDetailVO;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketVO;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单接口(Phase 13 第一步:创建 + AI 自动回答 + 我的列表/详情)。
 *
 * <p><strong>为什么工单挂企业作用域路径?</strong>
 * 工单由企业成员提交、企业客服处理、AI 检索企业知识库作答——数据与权限都
 * 以企业为边界。挂在 {@code /api/enterprises/{enterpriseId}/tickets} 后,
 * {@code EnterpriseContextFilter} 在进入本控制器前统一完成:
 * 登录校验(401)、{@code X-Enterprise-Id} 头存在且与路径一致(400)、
 * 成员身份与角色校验(403)。Controller 内无需重复这些校验。</p>
 *
 * <p><strong>userId 从认证主体取(与 EnterpriseController 同模式):</strong>
 * {@code EnterpriseContextFilter} 校验通过后重建的主体携带
 * {@code EnterpriseUser(userId, currentEnterpriseId, roleCode)},
 * 每个方法从 {@code authentication.getPrincipal()} 取 userId——
 * 客户端声明的身份不可信,服务端从可信来源取。</p>
 *
 * <p>本步接口面向普通成员(提工单看自己的工单),无需 {@code @PreAuthorize}
 * 权限码;客服视角的管理接口(全部工单/分配)是后续步骤。</p>
 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * 创建工单:落库(status=OPEN)并触发 AI 自动回答。
     *
     * <p>返回详情(工单 + AI 回复):创建后前端立即可展示 AI 的自动回答
     * 或「已转人工」提示,无需二次请求。</p>
     *
     * @param authentication 当前登录用户认证信息
     * @param enterpriseId   路径中的企业 ID(已通过企业上下文校验)
     * @param request        创建请求(title/description/category/priority)
     */
    @PostMapping
    public Result<TicketDetailVO> createTicket(Authentication authentication,
                                               @PathVariable Long enterpriseId,
                                               @Valid @RequestBody TicketCreateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(ticketService.createTicket(enterpriseId, userId, request));
    }

    /**
     * 我的工单列表:当前用户在该企业提交的全部工单(按创建时间倒序)。
     */
    @GetMapping
    public Result<List<TicketVO>> listMyTickets(Authentication authentication,
                                                @PathVariable Long enterpriseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(ticketService.listMyTickets(enterpriseId, userId));
    }

    /**
     * 工单详情:工单 + 全部回复(按创建时间升序)。
     *
     * <p>可见范围:提交人 或 客服(被分配处理人 / 企业 OWNER/ADMIN);
     * 不存在/无权访问统一 404(不泄露存在性)。角色编码随认证主体传入,
     * 服务端据此判定「管理兜底」可见性。</p>
     */
    @GetMapping("/{ticketId}")
    public Result<TicketDetailVO> getTicketDetail(Authentication authentication,
                                                  @PathVariable Long enterpriseId,
                                                  @PathVariable Long ticketId) {
        EnterpriseUser user = (EnterpriseUser) authentication.getPrincipal();
        return Result.success(
                ticketService.getTicketDetail(enterpriseId, user.userId(), user.roleCode(), ticketId));
    }

    /**
     * 分配工单:管理员把工单分配给某企业成员(客服),OPEN → ASSIGNED。
     *
     * <p>仅企业 OWNER/ADMIN 可操作;工单必须处于 OPEN 状态;
     * 目标客服必须是该企业正常成员。语义上是「更新工单的处理人」,
     * 用 PUT(幂等:同一分配重复请求结果一致——第二次会因状态已非 OPEN 返回 409,
     * 与「分配已完成」的幂等语义不冲突)。</p>
     */
    @PutMapping("/{ticketId}/assign")
    public Result<TicketVO> assignTicket(Authentication authentication,
                                         @PathVariable Long enterpriseId,
                                         @PathVariable Long ticketId,
                                         @Valid @RequestBody TicketAssignRequest request) {
        EnterpriseUser user = (EnterpriseUser) authentication.getPrincipal();
        return Result.success(
                ticketService.assignTicket(enterpriseId, user.userId(), user.roleCode(),
                        ticketId, request.getAssigneeId()));
    }

    /**
     * 工单回复:提交人补充说明(USER)或客服处理回复(SUPPORT)。
     *
     * <p>回复角色由服务端按身份判定,客户端不可指定;客服回复若工单处于
     * OPEN/ASSIGNED,状态自动推进为 PROCESSING。返回完整详情,
     * 前端回复后立即可刷新对话流。</p>
     */
    @PostMapping("/{ticketId}/replies")
    public Result<TicketDetailVO> addReply(Authentication authentication,
                                           @PathVariable Long enterpriseId,
                                           @PathVariable Long ticketId,
                                           @Valid @RequestBody TicketReplyCreateRequest request) {
        EnterpriseUser user = (EnterpriseUser) authentication.getPrincipal();
        return Result.success(
                ticketService.addReply(enterpriseId, user.userId(), user.roleCode(), ticketId, request));
    }

    /**
     * 工单状态流转:按状态机校验并推进(仅 PROCESSING/RESOLVED/CLOSED 可作为目标)。
     *
     * <p>合法流转与操作者权限由服务端状态机表判定,非法跳变/回退返回 409,
     * 无权操作返回 403。</p>
     */
    @PutMapping("/{ticketId}/status")
    public Result<TicketVO> updateTicketStatus(Authentication authentication,
                                               @PathVariable Long enterpriseId,
                                               @PathVariable Long ticketId,
                                               @Valid @RequestBody TicketStatusUpdateRequest request) {
        EnterpriseUser user = (EnterpriseUser) authentication.getPrincipal();
        return Result.success(
                ticketService.updateTicketStatus(enterpriseId, user.userId(), user.roleCode(),
                        ticketId, request));
    }
}
