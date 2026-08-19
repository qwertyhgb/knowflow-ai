package io.github.qwertyhgb.knowflow.ticket.service.impl;

import io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService;
import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketCreateRequest;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketReplyCreateRequest;
import io.github.qwertyhgb.knowflow.ticket.dto.request.TicketStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.ticket.entity.Ticket;
import io.github.qwertyhgb.knowflow.ticket.entity.TicketReply;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketCategory;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketPriority;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketReplyRole;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketStatus;
import io.github.qwertyhgb.knowflow.ticket.mapper.TicketMapper;
import io.github.qwertyhgb.knowflow.ticket.mapper.TicketReplyMapper;
import io.github.qwertyhgb.knowflow.ticket.service.TicketService;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketDetailVO;
import io.github.qwertyhgb.knowflow.ticket.vo.TicketVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TicketServiceImpl} 单元测试:纯 Mockito,验证创建 + AI 自动回答三分支 + 列表/详情。
 *
 * <p>测试核心:
 * 1. 创建——工单落库(status=OPEN、assignee=null)+ AI 回复落库(role=AI、sender=null、含 citations);
 * 2. AI 有相关块——LLM 回答含引用 JSON;
 * 3. AI 无相关块——Human Handoff 转人工提示,不调 LLM;
 * 4. AI 调用失败——降级转人工,工单仍创建成功;
 * 5. ChatClient 未装配——降级转人工,不检索不调 LLM;
 * 6. 列表/详情映射与归属校验(他人工单/跨企业工单抛 TICKET_NOT_FOUND)。</p>
 *
 * <p>【为什么时间用固定 Clock?】与项目其他 Service 单测一致:注入 {@code Clock.fixed}
 * 冻结时间,使断言精确可控,不依赖真实系统时钟。</p>
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private TicketReplyMapper ticketReplyMapper;

    @Mock
    private SemanticSearchService semanticSearchService;

    @Mock
    private ObjectProvider<ChatClient> chatClientProvider;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec promptSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    private EnterpriseMemberMapper enterpriseMemberMapper;

    private TicketService ticketService;

    private static final Instant FIXED_TIME = Instant.parse("2026-08-18T08:00:00Z");

    @BeforeEach
    void setUp() {
        ticketService = new TicketServiceImpl(
                ticketMapper, ticketReplyMapper, semanticSearchService,
                chatClientProvider, new tools.jackson.databind.json.JsonMapper(),
                Clock.fixed(FIXED_TIME, ZoneOffset.UTC), enterpriseMemberMapper);
    }

    // ==================== 创建工单 ====================

    @Test
    void shouldCreateTicketWithAiReplyAndCitations() {
        // 场景:知识库有相关块(score 0.85)→ LLM 生成带引用的回答
        when(ticketMapper.insert(any(Ticket.class))).thenAnswer(invocation -> {
            // 模拟 MyBatis-Plus 自动回填自增主键
            Ticket ticket = invocation.getArgument(0);
            ticket.setId(100L);
            return 1;
        });
        when(ticketReplyMapper.insert(any(TicketReply.class))).thenAnswer(invocation -> {
            TicketReply reply = invocation.getArgument(0);
            reply.setId(200L);
            return 1;
        });
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(semanticSearchService.search(eq("系统登录不上怎么办"), eq(5)))
                .thenReturn(List.of(searchResult("常见问题.md", "登录失败请先检查网络连接", 0.85)));
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(chatResponse("根据[1],登录失败请先检查网络连接"));

        TicketDetailVO detail = ticketService.createTicket(10L, 7L, request());

        // 工单落库:status=OPEN、assignee=null、归属企业与提交人正确
        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).insert(ticketCaptor.capture());
        Ticket inserted = ticketCaptor.getValue();
        assertEquals(10L, inserted.getEnterpriseId());
        assertEquals(7L, inserted.getUserId());
        assertEquals(TicketCategory.ISSUE, inserted.getCategory());
        assertEquals(TicketPriority.HIGH, inserted.getPriority());
        assertEquals(TicketStatus.OPEN, inserted.getStatus());
        assertNull(inserted.getAssigneeId(), "创建时尚未分配客服,assignee 应为 null");
        assertEquals("系统登录不上", inserted.getTitle());
        assertEquals(FIXED_TIME, inserted.getCreatedAt());
        assertEquals(FIXED_TIME, inserted.getUpdatedAt());

        // AI 回复落库:role=AI、sender=null、content 为 LLM 回答、citations 含来源文件名
        ArgumentCaptor<TicketReply> replyCaptor = ArgumentCaptor.forClass(TicketReply.class);
        verify(ticketReplyMapper).insert(replyCaptor.capture());
        TicketReply reply = replyCaptor.getValue();
        assertEquals(100L, reply.getTicketId());
        assertEquals(TicketReplyRole.AI, reply.getRole());
        assertNull(reply.getSenderId(), "AI 回复无用户身份,sender 应为 null");
        assertEquals("根据[1],登录失败请先检查网络连接", reply.getContent());
        assertTrue(reply.getCitations().contains("常见问题.md"),
                "AI 回复应持久化引用 JSON,包含来源文件名");
        assertTrue(reply.getCitations().startsWith("["), "citations 应是 JSON 数组");

        // 返回详情:工单 + 已生成的 AI 回复
        assertEquals(100L, detail.getTicket().getId());
        assertEquals(TicketStatus.OPEN, detail.getTicket().getStatus());
        assertEquals(1, detail.getReplies().size());
        assertEquals(TicketReplyRole.AI, detail.getReplies().get(0).getRole());
        assertEquals("根据[1],登录失败请先检查网络连接", detail.getReplies().get(0).getContent());
        assertTrue(detail.getReplies().get(0).getCitationsJson().contains("常见问题.md"));
    }

    @Test
    void shouldHandoffToHumanWhenNoRelevantChunks() {
        // 场景:检索有结果但全部低于阈值(0.1 < 0.3)→ Human Handoff,不调 LLM
        when(ticketMapper.insert(any(Ticket.class))).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            ticket.setId(101L);
            return 1;
        });
        when(ticketReplyMapper.insert(any(TicketReply.class))).thenAnswer(invocation -> {
            TicketReply reply = invocation.getArgument(0);
            reply.setId(201L);
            return 1;
        });
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(semanticSearchService.search(anyString(), eq(5)))
                .thenReturn(List.of(searchResult("无关文档.md", "完全不相关的内容", 0.1)));

        TicketDetailVO detail = ticketService.createTicket(10L, 7L, request());

        // 关键断言:一次 LLM 都没调(无依据时调用只会得到编造的幻觉回答)
        verify(chatClient, never()).prompt();

        // 落的是转人工提示:role=AI、citations=null
        ArgumentCaptor<TicketReply> replyCaptor = ArgumentCaptor.forClass(TicketReply.class);
        verify(ticketReplyMapper).insert(replyCaptor.capture());
        TicketReply reply = replyCaptor.getValue();
        assertEquals(TicketReplyRole.AI, reply.getRole());
        assertTrue(reply.getContent().contains("转人工客服处理"),
                "无相关块时应落 Human Handoff 转人工提示");
        assertNull(reply.getCitations(), "转人工提示没有任何知识库依据,citations 应为 null");

        // 工单仍然创建成功且状态保持 OPEN(等待人工分配)
        assertEquals(TicketStatus.OPEN, detail.getTicket().getStatus());
        assertEquals(1, detail.getReplies().size());
    }

    @Test
    void shouldFallbackToHumanWhenLlmFails() {
        // 场景:有相关块但 LLM 调用抛异常 → 降级转人工,工单仍创建成功
        when(ticketMapper.insert(any(Ticket.class))).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            ticket.setId(102L);
            return 1;
        });
        when(ticketReplyMapper.insert(any(TicketReply.class))).thenAnswer(invocation -> {
            TicketReply reply = invocation.getArgument(0);
            reply.setId(202L);
            return 1;
        });
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(semanticSearchService.search(anyString(), eq(5)))
                .thenReturn(List.of(searchResult("常见问题.md", "登录失败请先检查网络连接", 0.85)));
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenThrow(new RuntimeException("LLM_TIMEOUT"));

        TicketDetailVO detail = ticketService.createTicket(10L, 7L, request());

        // AI 是增强能力:LLM 失败不阻塞工单创建,降级为转人工提示
        assertEquals(102L, detail.getTicket().getId());
        assertEquals(TicketStatus.OPEN, detail.getTicket().getStatus());

        ArgumentCaptor<TicketReply> replyCaptor = ArgumentCaptor.forClass(TicketReply.class);
        verify(ticketReplyMapper).insert(replyCaptor.capture());
        assertTrue(replyCaptor.getValue().getContent().contains("转人工客服处理"),
                "LLM 失败应降级落转人工提示");
        assertNull(replyCaptor.getValue().getCitations());
    }

    @Test
    void shouldFallbackToHumanWhenChatClientUnavailable() {
        // 场景:未配置 api-key(ChatClient 未装配)→ 降级转人工,连检索都不做
        when(ticketMapper.insert(any(Ticket.class))).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            ticket.setId(103L);
            return 1;
        });
        when(ticketReplyMapper.insert(any(TicketReply.class))).thenAnswer(invocation -> {
            TicketReply reply = invocation.getArgument(0);
            reply.setId(203L);
            return 1;
        });
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        TicketDetailVO detail = ticketService.createTicket(10L, 7L, request());

        // AI 不可用时直接降级:不检索(检索了也没法生成回答,省一次 ES 调用)
        verify(semanticSearchService, never()).search(anyString(), org.mockito.ArgumentMatchers.anyInt());

        assertEquals(103L, detail.getTicket().getId());
        ArgumentCaptor<TicketReply> replyCaptor = ArgumentCaptor.forClass(TicketReply.class);
        verify(ticketReplyMapper).insert(replyCaptor.capture());
        assertTrue(replyCaptor.getValue().getContent().contains("转人工客服处理"));
        assertNull(replyCaptor.getValue().getCitations());
    }

    // ==================== 我的工单列表 ====================

    @Test
    void shouldListMyTickets() {
        // 排序在 SQL 层完成(orderByDesc),单测验证映射与过滤条件由 wrapper 携带
        when(ticketMapper.selectList(any())).thenReturn(List.of(
                ticket(2L, 10L, 7L, Instant.parse("2026-08-18T07:00:00Z")),
                ticket(1L, 10L, 7L, Instant.parse("2026-08-18T06:00:00Z"))));

        List<TicketVO> list = ticketService.listMyTickets(10L, 7L);

        assertEquals(2, list.size());
        assertEquals(2L, list.get(0).getId());
        assertEquals(TicketStatus.OPEN, list.get(0).getStatus());
        assertEquals("系统登录不上", list.get(0).getTitle());
        assertEquals(1L, list.get(1).getId());
    }

    // ==================== 工单详情 ====================

    @Test
    void shouldGetTicketDetailWithRepliesAscending() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 7L, FIXED_TIME));
        when(ticketReplyMapper.selectList(any())).thenReturn(List.of(
                reply(11L, 1L, TicketReplyRole.AI, "根据[1],请检查网络", Instant.parse("2026-08-18T08:00:00Z")),
                reply(12L, 1L, TicketReplyRole.SUPPORT, "已为您重置账号", Instant.parse("2026-08-18T09:00:00Z"))));

        TicketDetailVO detail = ticketService.getTicketDetail(10L, 7L, "MEMBER", 1L);

        assertEquals(1L, detail.getTicket().getId());
        assertEquals(2, detail.getReplies().size());
        // 升序回放:AI 自动回答在前,人工客服在后(排序由 SQL orderByAsc 保证)
        assertEquals(TicketReplyRole.AI, detail.getReplies().get(0).getRole());
        assertEquals(TicketReplyRole.SUPPORT, detail.getReplies().get(1).getRole());
        assertNull(detail.getReplies().get(0).getSenderId(), "AI 回复 sender 为 null");
        assertEquals(7L, detail.getReplies().get(1).getSenderId());
    }

    @Test
    void shouldThrowNotFoundForOthersTicket() {
        // 场景:工单存在但当前用户既非提交人也非客服(MEMBER)→ 统一 404,不泄露存在性
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 999L, FIXED_TIME));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.getTicketDetail(10L, 7L, "MEMBER", 1L));

        assertEquals(ErrorCode.TICKET_NOT_FOUND, exception.getErrorCode());
        verify(ticketReplyMapper, never()).selectList(any());
    }

    @Test
    void shouldThrowNotFoundForCrossEnterpriseTicket() {
        // 场景:工单属于另一家企业(跨企业枚举)→ 统一 404
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 99L, 7L, FIXED_TIME));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.getTicketDetail(10L, 7L, "MEMBER", 1L));

        assertEquals(ErrorCode.TICKET_NOT_FOUND, exception.getErrorCode());
        verify(ticketReplyMapper, never()).selectList(any());
    }

    @Test
    void shouldThrowNotFoundWhenTicketMissing() {
        when(ticketMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.getTicketDetail(10L, 7L, "MEMBER", 99L));

        assertEquals(ErrorCode.TICKET_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void shouldAllowAssigneeToViewDetail() {
        // 场景:被分配客服(普通 MEMBER)查看他人提交的工单 → 协作处理需要看详情
        Ticket assigned = ticket(1L, 10L, 5L, FIXED_TIME);
        assigned.setAssigneeId(7L);
        assigned.setStatus(TicketStatus.ASSIGNED);
        when(ticketMapper.selectById(1L)).thenReturn(assigned);
        when(ticketReplyMapper.selectList(any())).thenReturn(List.of());

        TicketDetailVO detail = ticketService.getTicketDetail(10L, 7L, "MEMBER", 1L);

        assertEquals(1L, detail.getTicket().getId());
        assertEquals(7L, detail.getTicket().getAssigneeId());
    }

    @Test
    void shouldAllowManagerToViewDetail() {
        // 场景:OWNER(管理兜底,未被分配)查看他人工单详情 → 允许
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 5L, FIXED_TIME));
        when(ticketReplyMapper.selectList(any())).thenReturn(List.of());

        TicketDetailVO detail = ticketService.getTicketDetail(10L, 7L, "OWNER", 1L);

        assertEquals(1L, detail.getTicket().getId());
    }

    // ==================== 分配工单(Phase 13 第二步)====================

    @Test
    void shouldAssignTicketAsAdmin() {
        // 场景:ADMIN 把 OPEN 工单分配给成员 8 → ASSIGNED + assignee 落库
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 7L, FIXED_TIME));
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member(10L, 8L));

        TicketVO vo = ticketService.assignTicket(10L, 2L, "ADMIN", 1L, 8L);

        assertEquals(TicketStatus.ASSIGNED, vo.getStatus());
        assertEquals(8L, vo.getAssigneeId());

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).updateById(captor.capture());
        assertEquals(TicketStatus.ASSIGNED, captor.getValue().getStatus());
        assertEquals(8L, captor.getValue().getAssigneeId());
        assertEquals(FIXED_TIME, captor.getValue().getUpdatedAt());
    }

    @Test
    void shouldRejectAssignByMember() {
        // 场景:普通 MEMBER 分配 → 403(分配是管理动作)
        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.assignTicket(10L, 7L, "MEMBER", 1L, 8L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(ticketMapper, never()).selectById(any());
    }

    @Test
    void shouldRejectAssignWhenNotOpen() {
        // 场景:工单已处理中(PROCESSING)再分配 → 409 状态冲突(不支持改派)
        Ticket processing = ticket(1L, 10L, 7L, FIXED_TIME);
        processing.setStatus(TicketStatus.PROCESSING);
        processing.setAssigneeId(8L);
        when(ticketMapper.selectById(1L)).thenReturn(processing);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.assignTicket(10L, 2L, "ADMIN", 1L, 9L));

        assertEquals(ErrorCode.TICKET_INVALID_TRANSITION, exception.getErrorCode());
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectAssignToNonMember() {
        // 场景:目标客服不是该企业正常成员 → 400(参数不满足业务前提)
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 7L, FIXED_TIME));
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.assignTicket(10L, 2L, "ADMIN", 1L, 999L));

        assertEquals(ErrorCode.INVALID_PARAMETER, exception.getErrorCode());
        verify(ticketMapper, never()).updateById(any(Ticket.class));
    }

    // ==================== 工单回复(Phase 13 第二步)====================

    @Test
    void shouldAddUserReplyAsSubmitterWithoutStatusChange() {
        // 场景:提交人(MEMBER)补充说明 → role=USER、状态保持 OPEN 不变
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 7L, FIXED_TIME));
        when(ticketReplyMapper.selectList(any())).thenReturn(List.of());

        TicketDetailVO detail = ticketService.addReply(10L, 7L, "MEMBER", 1L, replyRequest("附件是报错截图"));

        ArgumentCaptor<TicketReply> captor = ArgumentCaptor.forClass(TicketReply.class);
        verify(ticketReplyMapper).insert(captor.capture());
        assertEquals(TicketReplyRole.USER, captor.getValue().getRole());
        assertEquals(7L, captor.getValue().getSenderId());
        assertEquals("附件是报错截图", captor.getValue().getContent());
        // USER 回复不触发状态更新
        verify(ticketMapper, never()).updateById(any(Ticket.class));
        assertEquals(TicketStatus.OPEN, detail.getTicket().getStatus());
    }

    @Test
    void shouldAddSupportReplyAndAutoAdvanceToProcessing() {
        // 场景:被分配客服(普通 MEMBER)在 ASSIGNED 工单上回复 → role=SUPPORT、自动推进 PROCESSING
        Ticket assigned = ticket(1L, 10L, 7L, FIXED_TIME);
        assigned.setStatus(TicketStatus.ASSIGNED);
        assigned.setAssigneeId(8L);
        when(ticketMapper.selectById(1L)).thenReturn(assigned);
        when(ticketReplyMapper.selectList(any())).thenReturn(List.of());

        TicketDetailVO detail = ticketService.addReply(10L, 8L, "MEMBER", 1L, replyRequest("正在排查,请稍候"));

        ArgumentCaptor<TicketReply> replyCaptor = ArgumentCaptor.forClass(TicketReply.class);
        verify(ticketReplyMapper).insert(replyCaptor.capture());
        assertEquals(TicketReplyRole.SUPPORT, replyCaptor.getValue().getRole());
        assertEquals(8L, replyCaptor.getValue().getSenderId());

        // 自动推进:ASSIGNED → PROCESSING,且不覆盖已有 assignee
        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).updateById(ticketCaptor.capture());
        assertEquals(TicketStatus.PROCESSING, ticketCaptor.getValue().getStatus());
        assertEquals(8L, ticketCaptor.getValue().getAssigneeId());
        assertEquals(TicketStatus.PROCESSING, detail.getTicket().getStatus());
    }

    @Test
    void shouldAdvanceOpenTicketAndSetAssigneeOnManagerReply() {
        // 场景:OWNER 兜底直接回复 OPEN 工单(未分配)→ SUPPORT、OPEN → PROCESSING、
        // 处理人记为首个介入的管理员(维持「非 OPEN 必有处理人」不变量)
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 7L, FIXED_TIME));
        when(ticketReplyMapper.selectList(any())).thenReturn(List.of());

        TicketDetailVO detail = ticketService.addReply(10L, 2L, "OWNER", 1L, replyRequest("我来处理"));

        assertEquals(TicketStatus.PROCESSING, detail.getTicket().getStatus());
        assertEquals(2L, detail.getTicket().getAssigneeId());
    }

    @Test
    void shouldRejectReplyFromNonParticipant() {
        // 场景:普通成员(非提交人、非客服)回复 → 403
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 7L, FIXED_TIME));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.addReply(10L, 9L, "MEMBER", 1L, replyRequest("路过插一句")));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(ticketReplyMapper, never()).insert(any(TicketReply.class));
    }

    @Test
    void shouldThrowNotFoundWhenReplyTicketMissing() {
        when(ticketMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.addReply(10L, 7L, "MEMBER", 99L, replyRequest("内容")));

        assertEquals(ErrorCode.TICKET_NOT_FOUND, exception.getErrorCode());
    }

    // ==================== 状态流转(Phase 13 第二步)====================

    @Test
    void shouldTransitionAssignedToProcessingByAssignee() {
        // 场景:被分配客服手动开始处理 → ASSIGNED → PROCESSING
        Ticket assigned = ticket(1L, 10L, 7L, FIXED_TIME);
        assigned.setStatus(TicketStatus.ASSIGNED);
        assigned.setAssigneeId(8L);
        when(ticketMapper.selectById(1L)).thenReturn(assigned);

        TicketVO vo = ticketService.updateTicketStatus(10L, 8L, "MEMBER", 1L, statusUpdate(TicketStatus.PROCESSING));

        assertEquals(TicketStatus.PROCESSING, vo.getStatus());
        verify(ticketMapper).updateById(any(Ticket.class));
    }

    @Test
    void shouldTransitionProcessingToResolvedByAssignee() {
        // 场景:被分配客服标记解决 → PROCESSING → RESOLVED
        Ticket processing = ticket(1L, 10L, 7L, FIXED_TIME);
        processing.setStatus(TicketStatus.PROCESSING);
        processing.setAssigneeId(8L);
        when(ticketMapper.selectById(1L)).thenReturn(processing);

        TicketVO vo = ticketService.updateTicketStatus(10L, 8L, "MEMBER", 1L, statusUpdate(TicketStatus.RESOLVED));

        assertEquals(TicketStatus.RESOLVED, vo.getStatus());
    }

    @Test
    void shouldTransitionResolvedToClosedBySubmitter() {
        // 场景:提交人确认关闭 → RESOLVED → CLOSED(用户认可已解决)
        Ticket resolved = ticket(1L, 10L, 7L, FIXED_TIME);
        resolved.setStatus(TicketStatus.RESOLVED);
        resolved.setAssigneeId(8L);
        when(ticketMapper.selectById(1L)).thenReturn(resolved);

        TicketVO vo = ticketService.updateTicketStatus(10L, 7L, "MEMBER", 1L, statusUpdate(TicketStatus.CLOSED));

        assertEquals(TicketStatus.CLOSED, vo.getStatus());
    }

    @Test
    void shouldTransitionProcessingToClosedByAssignee() {
        // 场景:客服直接关闭处理中的工单(无效工单等无需用户确认的场景)
        Ticket processing = ticket(1L, 10L, 7L, FIXED_TIME);
        processing.setStatus(TicketStatus.PROCESSING);
        processing.setAssigneeId(8L);
        when(ticketMapper.selectById(1L)).thenReturn(processing);

        TicketVO vo = ticketService.updateTicketStatus(10L, 8L, "MEMBER", 1L, statusUpdate(TicketStatus.CLOSED));

        assertEquals(TicketStatus.CLOSED, vo.getStatus());
    }

    @Test
    void shouldRejectOpenToClosedJump() {
        // 场景:OPEN 直接 CLOSED(跳变,未处理不能关)→ 409;即使操作者是 OWNER 也拒绝
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 7L, FIXED_TIME));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.updateTicketStatus(10L, 2L, "OWNER", 1L, statusUpdate(TicketStatus.CLOSED)));

        assertEquals(ErrorCode.TICKET_INVALID_TRANSITION, exception.getErrorCode());
        verify(ticketMapper, never()).updateById(any(Ticket.class));
    }

    @Test
    void shouldRejectAssignedToResolvedJump() {
        // 场景:ASSIGNED 直接 RESOLVED(跳过处理环节)→ 409
        Ticket assigned = ticket(1L, 10L, 7L, FIXED_TIME);
        assigned.setStatus(TicketStatus.ASSIGNED);
        assigned.setAssigneeId(8L);
        when(ticketMapper.selectById(1L)).thenReturn(assigned);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.updateTicketStatus(10L, 8L, "ADMIN", 1L, statusUpdate(TicketStatus.RESOLVED)));

        assertEquals(ErrorCode.TICKET_INVALID_TRANSITION, exception.getErrorCode());
    }

    @Test
    void shouldRejectResolveBySubmitter() {
        // 场景:提交人(非客服)标记解决 → 403(是否解决由处理方说了算)
        Ticket processing = ticket(1L, 10L, 7L, FIXED_TIME);
        processing.setStatus(TicketStatus.PROCESSING);
        processing.setAssigneeId(8L);
        when(ticketMapper.selectById(1L)).thenReturn(processing);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.updateTicketStatus(10L, 7L, "MEMBER", 1L, statusUpdate(TicketStatus.RESOLVED)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(ticketMapper, never()).updateById(any(Ticket.class));
    }

    @Test
    void shouldRejectDirectlySettingOpen() {
        // 场景:直接把状态设为 OPEN → 400(OPEN/ASSIGNED 由动作驱动,不能直接设置)
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, 10L, 7L, FIXED_TIME));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.updateTicketStatus(10L, 2L, "OWNER", 1L, statusUpdate(TicketStatus.OPEN)));

        assertEquals(ErrorCode.INVALID_PARAMETER, exception.getErrorCode());
    }

    @Test
    void shouldRejectCloseFromResolvedByThirdParty() {
        // 场景:无关成员(非提交人非客服)确认关闭 → 403
        Ticket resolved = ticket(1L, 10L, 7L, FIXED_TIME);
        resolved.setStatus(TicketStatus.RESOLVED);
        resolved.setAssigneeId(8L);
        when(ticketMapper.selectById(1L)).thenReturn(resolved);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.updateTicketStatus(10L, 9L, "MEMBER", 1L, statusUpdate(TicketStatus.CLOSED)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void shouldThrowNotAssignedForDataDrift() {
        // 防御分支:ASSIGNED 却无处理人(数据漂移,正常流程不可能)→ TICKET_NOT_ASSIGNED
        Ticket assigned = ticket(1L, 10L, 7L, FIXED_TIME);
        assigned.setStatus(TicketStatus.ASSIGNED);
        assigned.setAssigneeId(null);
        when(ticketMapper.selectById(1L)).thenReturn(assigned);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.updateTicketStatus(10L, 2L, "ADMIN", 1L, statusUpdate(TicketStatus.PROCESSING)));

        assertEquals(ErrorCode.TICKET_NOT_ASSIGNED, exception.getErrorCode());
    }

    // ==================== 测试工厂方法 ====================

    /** 构造创建请求(与各用例共用的合法参数)。 */
    private TicketCreateRequest request() {
        TicketCreateRequest request = new TicketCreateRequest();
        request.setTitle("系统登录不上");
        request.setDescription("系统登录不上怎么办");
        request.setCategory(TicketCategory.ISSUE);
        request.setPriority(TicketPriority.HIGH);
        return request;
    }

    /** 构造检索结果块。 */
    private SemanticSearchVO searchResult(String fileName, String chunkText, double score) {
        return SemanticSearchVO.of(1L, 1L, fileName, 0, chunkText, score);
    }

    /** 构造 LLM 正常返回的 ChatResponse(工单场景不需要 token 统计)。 */
    private ChatResponse chatResponse(String reply) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }

    /** 构造测试工单。 */
    private Ticket ticket(Long id, Long enterpriseId, Long userId, Instant createdAt) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setEnterpriseId(enterpriseId);
        ticket.setUserId(userId);
        ticket.setCategory(TicketCategory.ISSUE);
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setTitle("系统登录不上");
        ticket.setDescription("系统登录不上怎么办");
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(createdAt);
        return ticket;
    }

    /** 构造测试回复。 */
    private TicketReply reply(Long id, Long ticketId, TicketReplyRole role, String content, Instant createdAt) {
        TicketReply reply = new TicketReply();
        reply.setId(id);
        reply.setTicketId(ticketId);
        reply.setRole(role);
        reply.setSenderId(role == TicketReplyRole.AI ? null : 7L);
        reply.setContent(content);
        reply.setCitations(null);
        reply.setCreatedAt(createdAt);
        return reply;
    }

    /** 构造企业正常成员记录(分配校验用)。 */
    private EnterpriseMember member(Long enterpriseId, Long userId) {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(enterpriseId);
        member.setUserId(userId);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        member.setRoleId(100L);
        return member;
    }

    /** 构造回复请求。 */
    private TicketReplyCreateRequest replyRequest(String content) {
        TicketReplyCreateRequest request = new TicketReplyCreateRequest();
        request.setContent(content);
        return request;
    }

    /** 构造状态流转请求。 */
    private TicketStatusUpdateRequest statusUpdate(TicketStatus status) {
        TicketStatusUpdateRequest request = new TicketStatusUpdateRequest();
        request.setStatus(status);
        return request;
    }
}
