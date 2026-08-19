package io.github.qwertyhgb.knowflow.ticket.controller;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRolePermissionMapper;
import io.github.qwertyhgb.knowflow.ticket.entity.Ticket;
import io.github.qwertyhgb.knowflow.ticket.entity.TicketReply;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketCategory;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketPriority;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketReplyRole;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketStatus;
import io.github.qwertyhgb.knowflow.ticket.service.TicketService;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketDetailVO;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketReplyVO;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TicketController} Web 层测试。
 *
 * <p>验证关键行为:
 * 1. 未登录 401;
 * 2. 创建工单 200(含 AI 回复的详情结构、UTC 时间格式);
 * 3. 我的工单列表 200;
 * 4. 详情 200 / 他人工单 404;
 * 5. 参数校验(title 空 / description 空 / category 非法)400;
 * 6. 缺 X-Enterprise-Id 头 400(企业上下文校验)。</p>
 *
 * <p><strong>为什么 mock 企业上下文三件套 Mapper?</strong>
 * 工单接口挂企业作用域路径,真实过滤器链中的 {@code EnterpriseContextFilter}
 * 会查 {@code EnterpriseMemberMapper}/{@code EnterpriseRoleMapper}/
 * {@code EnterpriseRolePermissionMapper} 校验成员身份并注入角色权限——
 * mock 后返回固定成员(企业 10、用户 7、OWNER 角色),请求即视为合法企业上下文。
 * {@code TokenService} 同理:对 valid-token 返回用户 ID 即视为已登录。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @MockitoBean
    private EnterpriseRoleMapper enterpriseRoleMapper;

    @MockitoBean
    private EnterpriseRolePermissionMapper enterpriseRolePermissionMapper;

    /** 已认证的请求头(TokenService.resolveUserId 对该 token 返回用户 ID 即视为已登录)。 */
    private static final String AUTH_HEADER = "Bearer valid-token";

    /** 测试固定的企业上下文:企业 10、用户 7(OWNER)。 */
    private static final long ENTERPRISE_ID = 10L;

    private static final long USER_ID = 7L;

    private static final Instant FIXED_TIME = Instant.parse("2026-08-18T08:00:00.123Z");

    @BeforeEach
    void stubEnterpriseContext() {
        // 企业上下文桩:用户 7 是企业 10 的正常成员,关联 OWNER 角色(100)。
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(ENTERPRISE_ID);
        member.setUserId(USER_ID);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        member.setRoleId(100L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);

        EnterpriseRole role = new EnterpriseRole();
        role.setId(100L);
        role.setEnterpriseId(ENTERPRISE_ID);
        role.setCode("OWNER");
        role.setName("OWNER");
        role.setStatus(EnterpriseRoleStatus.NORMAL);
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role);

        // 工单接口无 @PreAuthorize,权限码注入空列表即可(过滤器仍会调用此查询)。
        when(enterpriseRolePermissionMapper.selectPermissionCodesByRoleId(any()))
                .thenReturn(List.of());
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        // 不带认证头 → 401,验证工单接口默认受 Security 保护
        mockMvc.perform(post("/api/enterprises/10/tickets")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldCreateTicket() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        when(ticketService.createTicket(eq(ENTERPRISE_ID), eq(USER_ID), any()))
                .thenReturn(detail());

        mockMvc.perform(post("/api/enterprises/10/tickets")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.ticket.id").value(100))
                .andExpect(jsonPath("$.data.ticket.enterpriseId").value(10))
                .andExpect(jsonPath("$.data.ticket.userId").value(7))
                .andExpect(jsonPath("$.data.ticket.category").value("ISSUE"))
                .andExpect(jsonPath("$.data.ticket.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.data.ticket.assigneeId").doesNotExist())
                // 时间格式断言:统一 ISO-8601 UTC 字符串(毫秒精度、Z 结尾)
                .andExpect(jsonPath("$.data.ticket.createdAt").value("2026-08-18T08:00:00.123Z"))
                // 创建即返回 AI 回复:role=AI、senderId 不存在(null)、citationsJson 透传
                .andExpect(jsonPath("$.data.replies.length()").value(1))
                .andExpect(jsonPath("$.data.replies[0].role").value("AI"))
                .andExpect(jsonPath("$.data.replies[0].senderId").doesNotExist())
                .andExpect(jsonPath("$.data.replies[0].content").value("根据[1],请检查网络连接"))
                .andExpect(jsonPath("$.data.replies[0].citationsJson").isNotEmpty());

        verify(ticketService).createTicket(eq(ENTERPRISE_ID), eq(USER_ID), any());
    }

    @Test
    void shouldListMyTickets() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        when(ticketService.listMyTickets(ENTERPRISE_ID, USER_ID))
                .thenReturn(List.of(TicketVO.from(ticket())));

        mockMvc.perform(get("/api/enterprises/10/tickets")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.data[0].title").value("系统登录不上"))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"));

        verify(ticketService).listMyTickets(ENTERPRISE_ID, USER_ID);
    }

    @Test
    void shouldGetTicketDetail() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        when(ticketService.getTicketDetail(ENTERPRISE_ID, USER_ID, "OWNER", 100L)).thenReturn(detail());

        mockMvc.perform(get("/api/enterprises/10/tickets/100")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.ticket.id").value(100))
                .andExpect(jsonPath("$.data.replies.length()").value(1))
                .andExpect(jsonPath("$.data.replies[0].role").value("AI"));
    }

    @Test
    void shouldReturnNotFoundForOthersTicket() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        when(ticketService.getTicketDetail(ENTERPRISE_ID, USER_ID, "OWNER", 99L))
                .thenThrow(new BusinessException(ErrorCode.TICKET_NOT_FOUND));

        mockMvc.perform(get("/api/enterprises/10/tickets/99")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("工单不存在"));
    }

    @Test
    void shouldRejectMissingEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));

        // 企业作用域请求缺 X-Enterprise-Id → 400,由 EnterpriseContextFilter 拦截
        mockMvc.perform(post("/api/enterprises/10/tickets")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldRejectBlankTitle() throws Exception {
        assertInvalidRequest("""
                { "title": "   ", "description": "系统登录不上怎么办", "category": "ISSUE", "priority": "HIGH" }
                """);
    }

    @Test
    void shouldRejectBlankDescription() throws Exception {
        assertInvalidRequest("""
                { "title": "系统登录不上", "description": " ", "category": "ISSUE", "priority": "HIGH" }
                """);
    }

    @Test
    void shouldRejectInvalidCategory() throws Exception {
        // category 不在枚举定义内 → 反序列化失败,全局异常处理器兜底 400
        assertInvalidRequest("""
                { "title": "系统登录不上", "description": "系统登录不上怎么办", "category": "FOO", "priority": "HIGH" }
                """);
    }

    @Test
    void shouldRejectMissingPriority() throws Exception {
        assertInvalidRequest("""
                { "title": "系统登录不上", "description": "系统登录不上怎么办", "category": "ISSUE" }
                """);
    }

    // ==================== 分配/回复/状态流转(Phase 13 第二步)====================

    @Test
    void shouldAssignTicket() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        Ticket assigned = ticket();
        assigned.setStatus(TicketStatus.ASSIGNED);
        assigned.setAssigneeId(8L);
        // 桩上下文角色为 OWNER,Controller 应把认证主体上的角色编码透传给 Service
        when(ticketService.assignTicket(ENTERPRISE_ID, USER_ID, "OWNER", 100L, 8L))
                .thenReturn(TicketVO.from(assigned));

        mockMvc.perform(put("/api/enterprises/10/tickets/100/assign")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": 8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.assigneeId").value(8));

        verify(ticketService).assignTicket(ENTERPRISE_ID, USER_ID, "OWNER", 100L, 8L);
    }

    @Test
    void shouldReturnUnauthorizedForAssignWithoutToken() throws Exception {
        mockMvc.perform(put("/api/enterprises/10/tickets/100/assign")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": 8}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldRejectNonPositiveAssigneeId() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));

        // assigneeId=0 违反 @Positive → 400,不调用 Service
        mockMvc.perform(put("/api/enterprises/10/tickets/100/assign")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldReturnForbiddenWhenMemberAssigns() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        // Service 判定操作者非 OWNER/ADMIN → FORBIDDEN(角色判断在 Service 层,Web 层验证透传)
        when(ticketService.assignTicket(ENTERPRISE_ID, USER_ID, "OWNER", 100L, 8L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(put("/api/enterprises/10/tickets/100/assign")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": 8}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnNotFoundWhenAssignMissingTicket() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        when(ticketService.assignTicket(ENTERPRISE_ID, USER_ID, "OWNER", 99L, 8L))
                .thenThrow(new BusinessException(ErrorCode.TICKET_NOT_FOUND));

        mockMvc.perform(put("/api/enterprises/10/tickets/99/assign")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": 8}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturnConflictWhenAssignNonOpenTicket() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        when(ticketService.assignTicket(ENTERPRISE_ID, USER_ID, "OWNER", 100L, 8L))
                .thenThrow(new BusinessException(ErrorCode.TICKET_INVALID_TRANSITION));

        mockMvc.perform(put("/api/enterprises/10/tickets/100/assign")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": 8}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_INVALID_TRANSITION"))
                .andExpect(jsonPath("$.message").value("当前状态不允许该操作"));
    }

    @Test
    void shouldAddTicketReply() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        when(ticketService.addReply(eq(ENTERPRISE_ID), eq(USER_ID), eq("OWNER"), eq(100L),
                any(io.github.qwertyhgb.knowflow.ticket.dto.request.TicketReplyCreateRequest.class)))
                .thenReturn(detail());

        mockMvc.perform(post("/api/enterprises/10/tickets/100/replies")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"问题还在复现\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.ticket.id").value(100))
                .andExpect(jsonPath("$.data.replies.length()").value(1));

        verify(ticketService).addReply(eq(ENTERPRISE_ID), eq(USER_ID), eq("OWNER"), eq(100L),
                any(io.github.qwertyhgb.knowflow.ticket.dto.request.TicketReplyCreateRequest.class));
    }

    @Test
    void shouldReturnUnauthorizedForReplyWithoutToken() throws Exception {
        mockMvc.perform(post("/api/enterprises/10/tickets/100/replies")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"问题还在复现\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldRejectBlankReplyContent() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));

        mockMvc.perform(post("/api/enterprises/10/tickets/100/replies")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldReturnForbiddenWhenNonParticipantReplies() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        when(ticketService.addReply(eq(ENTERPRISE_ID), eq(USER_ID), eq("OWNER"), eq(100L),
                any(io.github.qwertyhgb.knowflow.ticket.dto.request.TicketReplyCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(post("/api/enterprises/10/tickets/100/replies")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"路过插一句\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void shouldUpdateTicketStatus() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        Ticket resolved = ticket();
        resolved.setStatus(TicketStatus.RESOLVED);
        when(ticketService.updateTicketStatus(eq(ENTERPRISE_ID), eq(USER_ID), eq("OWNER"), eq(100L),
                any(io.github.qwertyhgb.knowflow.ticket.dto.request.TicketStatusUpdateRequest.class)))
                .thenReturn(TicketVO.from(resolved));

        mockMvc.perform(put("/api/enterprises/10/tickets/100/status")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }

    @Test
    void shouldReturnUnauthorizedForStatusUpdateWithoutToken() throws Exception {
        mockMvc.perform(put("/api/enterprises/10/tickets/100/status")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldRejectMissingTargetStatus() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));

        mockMvc.perform(put("/api/enterprises/10/tickets/100/status")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldReturnConflictForInvalidTransition() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));
        // OPEN 直接 CLOSED(跳变)→ Service 状态机拒绝 409
        when(ticketService.updateTicketStatus(eq(ENTERPRISE_ID), eq(USER_ID), eq("OWNER"), eq(100L),
                any(io.github.qwertyhgb.knowflow.ticket.dto.request.TicketStatusUpdateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.TICKET_INVALID_TRANSITION));

        mockMvc.perform(put("/api/enterprises/10/tickets/100/status")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_INVALID_TRANSITION"));
    }

    // ==================== 测试工厂与辅助 ====================

    /** 断言非法请求体:400 + INVALID_PARAMETER,且不调用 Service。 */
    private void assertInvalidRequest(String content) throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(USER_ID));

        mockMvc.perform(post("/api/enterprises/10/tickets")
                        .header("Authorization", AUTH_HEADER)
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(ticketService);
    }

    /** 合法的创建请求体。 */
    private String validCreateBody() {
        return """
                { "title": "系统登录不上", "description": "系统登录不上怎么办", "category": "ISSUE", "priority": "HIGH" }
                """;
    }

    /** 构造测试工单实体(企业 10、用户 7、OPEN 状态、未分配)。 */
    private Ticket ticket() {
        Ticket ticket = new Ticket();
        ticket.setId(100L);
        ticket.setEnterpriseId(ENTERPRISE_ID);
        ticket.setUserId(USER_ID);
        ticket.setCategory(TicketCategory.ISSUE);
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setTitle("系统登录不上");
        ticket.setDescription("系统登录不上怎么办");
        ticket.setAssigneeId(null);
        ticket.setCreatedAt(FIXED_TIME);
        ticket.setUpdatedAt(FIXED_TIME);
        return ticket;
    }

    /** 构造工单详情(工单 + 一条带引用的 AI 回复)。 */
    private TicketDetailVO detail() {
        TicketReply aiReply = new TicketReply();
        aiReply.setId(200L);
        aiReply.setTicketId(100L);
        aiReply.setRole(TicketReplyRole.AI);
        aiReply.setSenderId(null);
        aiReply.setContent("根据[1],请检查网络连接");
        aiReply.setCitations("[{\"documentId\":1,\"fileName\":\"常见问题.md\",\"score\":0.85}]");
        aiReply.setCreatedAt(FIXED_TIME);
        return TicketDetailVO.of(TicketVO.from(ticket()), List.of(TicketReplyVO.from(aiReply)));
    }
}
