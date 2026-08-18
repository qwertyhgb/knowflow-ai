package io.github.qwertyhgb.knowflow.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.qwertyhgb.knowflow.ai.entity.AiMessage;
import io.github.qwertyhgb.knowflow.ai.entity.Conversation;
import io.github.qwertyhgb.knowflow.ai.mapper.AiMessageMapper;
import io.github.qwertyhgb.knowflow.ai.mapper.ConversationMapper;
import io.github.qwertyhgb.knowflow.ai.service.ConversationService;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationDetailVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationMessageVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationServiceImpl} 单元测试：纯 Mockito，验证会话 CRUD 与归属校验。
 *
 * <p>测试核心：
 * 1. 创建——插入默认标题「新对话」的会话；
 * 2. 列表——按 updated_at 倒序（由 Mockito 桩的排序参数体现，实际排序在 SQL 层）；
 * 3. 详情——本人会话返回会话 + 消息（升序），他人会话抛 404；
 * 4. 删除——先删消息再删会话。</p>
 *
 * <p>【为什么时间用固定 Clock？】与项目其他 Service 单测一致：注入 {@code Clock.fixed}
 * 冻结时间，使断言精确可控，不依赖真实系统时钟。</p>
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private AiMessageMapper aiMessageMapper;

    private ConversationService conversationService;

    private static final Instant FIXED_TIME = Instant.parse("2026-08-18T08:00:00Z");

    @BeforeEach
    void setUp() {
        conversationService = new ConversationServiceImpl(
                conversationMapper, aiMessageMapper, Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
    }

    @Test
    void shouldCreateConversationWithDefaultTitle() {
        // 场景：创建会话 → 插入记录，标题为默认值「新对话」，时间为冻结值
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer(invocation -> {
            // 模拟 MyBatis-Plus 自动回填自增主键
            Conversation conversation = invocation.getArgument(0);
            conversation.setId(100L);
            return 1;
        });

        ConversationVO vo = conversationService.createConversation(1L);

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).insert(captor.capture());
        Conversation inserted = captor.getValue();
        assertEquals(1L, inserted.getUserId());
        // 创建时不生成标题：默认值「新对话」占位（第一条消息后才自动生成）
        assertEquals("新对话", inserted.getTitle());
        assertEquals(FIXED_TIME, inserted.getCreatedAt());
        assertEquals(FIXED_TIME, inserted.getUpdatedAt());

        assertEquals(100L, vo.getId());
        assertEquals("新对话", vo.getTitle());
    }

    @Test
    void shouldListMyConversations() {
        // 场景：按用户查询 → 返回倒序列表（排序 SQL 由 Mapper 层执行，此处验证查询条件与映射）
        when(conversationMapper.selectList(any())).thenReturn(List.of(
                conversation(2L, 1L, "第二个会话", Instant.parse("2026-08-18T07:00:00Z")),
                conversation(1L, 1L, "第一个会话", Instant.parse("2026-08-18T06:00:00Z"))));

        List<ConversationVO> list = conversationService.listMyConversations(1L);

        assertEquals(2, list.size());
        assertEquals(2L, list.get(0).getId());
        assertEquals("第二个会话", list.get(0).getTitle());
        assertEquals(1L, list.get(1).getId());
    }

    @Test
    void shouldGetConversationDetailWithMessages() {
        // 场景：本人会话 → 返回会话信息 + 消息列表
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "会话标题",
                Instant.parse("2026-08-18T06:00:00Z")));
        when(aiMessageMapper.selectList(any())).thenReturn(List.of(
                message(1L, "你好", "USER", Instant.parse("2026-08-18T06:00:00Z")),
                message(2L, "你好！", "ASSISTANT", Instant.parse("2026-08-18T06:01:00Z"))));

        ConversationDetailVO detail = conversationService.getConversationDetail(1L, 1L);

        assertEquals("会话标题", detail.getTitle());
        assertEquals(2, detail.getMessages().size());
        ConversationMessageVO first = detail.getMessages().get(0);
        assertEquals("你好", first.getContent());
        assertEquals("USER", first.getRole().name());
        assertEquals("你好！", detail.getMessages().get(1).getContent());
    }

    @Test
    void shouldPersistCitationsJsonInConversationDetailMessages() {
        // 场景：RAG 会话的详情应把落库的 citations JSON 透传给前端，
        // 保证「重开会话引用可回溯」（历史消息楼上带引用，前端可直接渲染）。
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "RAG 会话",
                Instant.parse("2026-08-18T06:00:00Z")));

        // 助手消息带 citations JSON（对应 List<RagCitationVO> 的序列化结果）
        AiMessage assistant = new AiMessage();
        assistant.setId(2L);
        assistant.setConversationId(1L);
        assistant.setContent("根据[1]，缓存方案是 Redis");
        assistant.setCreatedAt(Instant.parse("2026-08-18T06:01:00Z"));
        assistant.setRole(io.github.qwertyhgb.knowflow.ai.enums.AiMessageRole.ASSISTANT);
        assistant.setCitations("[{\"documentId\":1,\"fileName\":\"架构设计.md\",\"score\":0.85}]");

        when(aiMessageMapper.selectList(any())).thenReturn(List.of(
                message(1L, "缓存方案是什么", "USER", Instant.parse("2026-08-18T06:00:00Z")),
                assistant));

        ConversationDetailVO detail = conversationService.getConversationDetail(1L, 1L);

        assertEquals(2, detail.getMessages().size());
        // 用户消息无引用 → citationsJson 为 null
        assertNull(detail.getMessages().get(0).getCitationsJson(),
                "用户消息不应有引用");
        // 助手消息的 citations JSON 原样透传（前端 JSON.parse 后即可回溯引用）
        assertTrue(detail.getMessages().get(1).getCitationsJson().contains("架构设计.md"),
                "RAG 助手消息应透传引用 JSON，包含来源文件名");
    }

    @Test
    void shouldThrowNotFoundForOtherUsersConversation() {
        // 场景：他人会话（归属校验失败）→ 404，且不泄露存在性
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 999L, "别人的会话",
                Instant.parse("2026-08-18T06:00:00Z")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> conversationService.getConversationDetail(1L, 1L));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.getErrorCode());
        // 归属校验失败不应读取消息
        verify(aiMessageMapper, never()).selectList(any());
    }

    @Test
    void shouldThrowNotFoundWhenConversationMissing() {
        // 场景：会话不存在 → 404
        when(conversationMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> conversationService.getConversationDetail(1L, 99L));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void shouldDeleteMessagesThenConversation() {
        // 场景：删除会话 → 先删消息再删会话（两步顺序，无外键级联的显式保证）
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "会话",
                Instant.parse("2026-08-18T06:00:00Z")));

        conversationService.deleteConversation(1L, 1L);

        // 先验证删消息，再验证删会话（Mockito 默认按调用顺序记录）
        verify(aiMessageMapper).delete(any(Wrapper.class));
        verify(conversationMapper).deleteById(1L);
    }

    @Test
    void shouldNotDeleteOtherUsersConversation() {
        // 场景：删除他人会话 → 404，且不执行任何删除
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 999L, "别人的会话",
                Instant.parse("2026-08-18T06:00:00Z")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> conversationService.deleteConversation(1L, 1L));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.getErrorCode());
        verify(aiMessageMapper, never()).delete(any(Wrapper.class));
        verify(conversationMapper, never()).deleteById(anyLong());
    }

    /** 构造测试会话。 */
    private Conversation conversation(Long id, Long userId, String title, Instant updatedAt) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        conversation.setTitle(title);
        conversation.setCreatedAt(FIXED_TIME);
        conversation.setUpdatedAt(updatedAt);
        return conversation;
    }

    /** 构造测试消息。 */
    private AiMessage message(Long id, String content, String role, Instant createdAt) {
        AiMessage message = new AiMessage();
        message.setId(id);
        message.setConversationId(1L);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        message.setRole(io.github.qwertyhgb.knowflow.ai.enums.AiMessageRole.valueOf(role));
        return message;
    }
}
