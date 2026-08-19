package io.github.qwertyhgb.knowflow.ai.service.impl;

import io.github.qwertyhgb.knowflow.ai.entity.AiMessage;
import io.github.qwertyhgb.knowflow.ai.entity.Conversation;
import io.github.qwertyhgb.knowflow.ai.enums.AiMessageRole;
import io.github.qwertyhgb.knowflow.ai.mapper.AiMessageMapper;
import io.github.qwertyhgb.knowflow.ai.mapper.ConversationMapper;
import io.github.qwertyhgb.knowflow.ai.rate.RateLimitService;
import io.github.qwertyhgb.knowflow.ai.service.ConversationChatService;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationChatVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationChatServiceImpl} 单元测试：mock Mapper 与 ChatClient 链。
 *
 * <p>测试核心：
 * 1. 正常多轮——用户消息落库、AI 消息落库含 token、历史窗口拼接、标题自动生成、
 *    updated_at 更新、user 消息含历史；
 * 2. 首条消息标题生成；
 * 3. 会话不存在抛 404；
 * 4. ChatClient 未装配 503；
 * 5. LLM 失败转 503 且用户消息已落库。</p>
 */
@ExtendWith(MockitoExtension.class)
class ConversationChatServiceImplTest {

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private AiMessageMapper aiMessageMapper;

    @Mock
    private ObjectProvider<ChatClient> chatClientProvider;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec promptSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    private io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService semanticSearchService;

    /** 用户维限流服务 mock：既有用例默认放行，限流触发由专用用例覆盖。 */
    @Mock
    private RateLimitService rateLimitService;

    private ConversationChatService chatService;

    private static final Instant FIXED_TIME = Instant.parse("2026-08-18T08:00:00Z");

    @BeforeEach
    void setUp() {
        chatService = new ConversationChatServiceImpl(
                conversationMapper, aiMessageMapper, chatClientProvider,
                semanticSearchService,
                new tools.jackson.databind.json.JsonMapper(),
                Clock.fixed(FIXED_TIME, ZoneOffset.UTC),
                rateLimitService);
        // 默认放行限流，让既有用例专注于会话对话逻辑；「超限拒绝」的用例单独桩返回 false。
        when(rateLimitService.tryAcquire(anyLong(), anyString())).thenReturn(true);
    }

    /** 构造 ChatResponse（含 usage），模拟 LLM 正常返回。 */
    private ChatResponse chatResponse(String reply, int inputTokens, int outputTokens) {
        AssistantMessage assistantMessage = new AssistantMessage(reply);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new Usage() {
                    @Override
                    public Integer getPromptTokens() {
                        return inputTokens;
                    }

                    @Override
                    public Integer getCompletionTokens() {
                        return outputTokens;
                    }

                    @Override
                    public Object getNativeUsage() {
                        return null;
                    }
                })
                .build();
        return new ChatResponse(List.of(new Generation(assistantMessage)), metadata);
    }

    /** 构造测试会话。 */
    private Conversation conversation(Long id, Long userId, String title) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        conversation.setTitle(title);
        conversation.setCreatedAt(FIXED_TIME);
        conversation.setUpdatedAt(FIXED_TIME);
        return conversation;
    }

    /** 构造测试消息（历史用）。 */
    private AiMessage message(Long id, String content, AiMessageRole role, Instant createdAt) {
        AiMessage message = new AiMessage();
        message.setId(id);
        message.setConversationId(1L);
        message.setContent(content);
        message.setRole(role);
        message.setCreatedAt(createdAt);
        return message;
    }

    @Test
    void shouldChatWithHistoryAndTitleGeneration() {
        // 场景：会话标题还是「新对话」（首条消息）→ 应自动生成标题 + 正常多轮回答
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "新对话"));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("我叫小明", 10, 5));
        // 历史为空（首条消息）：查询消息返回空
        when(aiMessageMapper.selectList(any())).thenReturn(new ArrayList<>());

        ConversationChatVO vo = chatService.chat(1L, 1L, "我叫小明");

        // 1. AI 回答 + token 统计
        assertEquals("我叫小明", vo.getReply());
        assertEquals(10, vo.getInputTokens());
        assertEquals(5, vo.getOutputTokens());

        // 2+4. 消息落库两次（用户消息 + AI 消息）——用 getAllValues 按插入顺序断言
        ArgumentCaptor<AiMessage> messageCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper, times(2)).insert(messageCaptor.capture());
        AiMessage firstInsert = messageCaptor.getAllValues().get(0);
        AiMessage secondInsert = messageCaptor.getAllValues().get(1);
        assertEquals(AiMessageRole.USER, firstInsert.getRole());
        assertEquals("我叫小明", firstInsert.getContent());
        assertEquals(AiMessageRole.ASSISTANT, secondInsert.getRole());
        assertEquals("我叫小明", secondInsert.getContent());
        assertEquals(10, secondInsert.getInputTokens());
        assertEquals(5, secondInsert.getOutputTokens());

        // 3. 标题自动生成：首条消息前 20 字符。
        // 【为什么 updateById 会被调用 2 次？】第一次是标题生成（首条消息），
        // 第二次是步骤 9 更新 updated_at——同一会话可能被更新两次，因此断言
        // 必须用 getAllValues 检查「至少有一次更新把标题设为目标值」。
        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper, times(2)).updateById(conversationCaptor.capture());
        boolean titleUpdated = conversationCaptor.getAllValues().stream()
                .anyMatch(c -> "我叫小明".equals(c.getTitle()));
        assertTrue(titleUpdated, "标题应被自动生成为首条消息前 20 字符");
    }

    @Test
    void shouldTruncateTitleTo20Chars() {
        // 场景：首条消息超 20 字符 → 标题截断为前 20 字符
        String longMessage = "这是一条非常非常非常非常非常非常长的首条消息超过二十个字符";
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "新对话"));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("回答", 1, 1));
        when(aiMessageMapper.selectList(any())).thenReturn(new ArrayList<>());

        chatService.chat(1L, 1L, longMessage);

        // updateById 被调用 2 次（标题生成 + updated_at），检查任意一次标题为截断值
        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper, times(2)).updateById(conversationCaptor.capture());
        boolean titleTruncated = conversationCaptor.getAllValues().stream()
                .anyMatch(c -> longMessage.substring(0, 20).equals(c.getTitle()));
        assertTrue(titleTruncated, "标题应被截断为前 20 字符");
    }

    @Test
    void shouldIncludeHistoryInPrompt() {
        // 场景：已有历史消息 → user 消息拼接历史 + 当前问题
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "已有标题"));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("回答", 1, 1));
        // 历史：一条用户消息 + 一条助手消息
        when(aiMessageMapper.selectList(any())).thenReturn(List.of(
                message(1L, "你好", AiMessageRole.USER, Instant.parse("2026-08-18T07:00:00Z")),
                message(2L, "你好！", AiMessageRole.ASSISTANT, Instant.parse("2026-08-18T07:01:00Z"))));

        chatService.chat(1L, 1L, "我叫什么名字");

        // 断言 user 消息包含历史拼接 + 当前问题
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("用户:你好"), "应包含历史用户消息");
        assertTrue(prompt.contains("助手:你好！"), "应包含历史助手消息");
        assertTrue(prompt.contains("用户:我叫什么名字"), "应包含当前问题");
    }

    @Test
    void shouldOnlyIncludeLast10HistoryMessages() {
        // 场景：历史超过 10 条 → 滑动窗口只取最近 10 条（升序取尾部）
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "已有标题"));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("回答", 1, 1));
        // 12 条历史消息（升序）
        List<AiMessage> history = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            history.add(message((long) i, "消息" + i, AiMessageRole.USER,
                    Instant.parse("2026-08-18T06:00:00Z").plusSeconds(i)));
        }
        when(aiMessageMapper.selectList(any())).thenReturn(history);

        chatService.chat(1L, 1L, "当前问题");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        // 最近 10 条：消息3 ~ 消息12（最早的「消息1」「消息2」被窗口丢弃）。
        // 【为什么用「用户:消息N\n」整行断言？】「消息1」是「消息12」的子串，
        // 裸 contains("消息1") 会误匹配；加行前缀后整行唯一，断言才可靠。
        assertTrue(prompt.contains("用户:消息3\n"), "窗口应包含第 3 条（第 1/2 条被丢弃）");
        assertTrue(prompt.contains("用户:消息12\n"), "窗口应包含第 12 条");
        assertTrue(!prompt.contains("用户:消息1\n"), "最早的「消息1」不应进入上下文");
        assertTrue(!prompt.contains("用户:消息2\n"), "最早的「消息2」不应进入上下文");
    }

    @Test
    void shouldThrowNotFoundForOtherUsersConversation() {
        // 场景：他人会话 → 404
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 999L, "别人的会话"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.chat(1L, 1L, "你好"));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.getErrorCode());
        // 归属校验失败不落任何消息
        verify(aiMessageMapper, never()).insert(any(AiMessage.class));
    }

    @Test
    void shouldThrowServiceUnavailableWhenChatClientNotConfigured() {
        // 场景：未配置 DEEPSEEK_API_KEY → ChatClient 未装配 → 503
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "已有标题"));
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.chat(1L, 1L, "你好"));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
        // ChatClient 不可用时不落消息（判空在保存用户消息之前）
        verify(aiMessageMapper, never()).insert(any(AiMessage.class));
    }

    @Test
    void shouldPersistUserMessageWhenLlmFails() {
        // 场景：LLM 调用失败 → 转 503，但用户消息已落库（可接受，见实现类注释）
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "新对话"));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenThrow(new RuntimeException("upstream timeout"));
        when(aiMessageMapper.selectList(any())).thenReturn(new ArrayList<>());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.chat(1L, 1L, "你好"));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
        // 用户消息已落库（一次 insert：用户消息）
        ArgumentCaptor<AiMessage> userCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper).insert(userCaptor.capture());
        assertEquals(AiMessageRole.USER, userCaptor.getValue().getRole());
    }

    @Test
    void shouldTolerateMissingUsage() {
        // 场景：模型未返回 usage → token 存 null，不阻塞主流程
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "新对话"));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        // usage 为 null：mock metadata 的 getUsage() 返回 null
        // （ChatResponseMetadata.builder().build() 会兜底一个 token 为 0 的空 Usage，
        // 不能真实模拟「缺失」，必须显式 mock 返回 null）
        ChatResponseMetadata metadata = org.mockito.Mockito.mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(null);
        when(callResponseSpec.chatResponse()).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("回答"))), metadata));
        when(aiMessageMapper.selectList(any())).thenReturn(new ArrayList<>());

        ConversationChatVO vo = chatService.chat(1L, 1L, "你好");

        assertEquals("回答", vo.getReply());
        assertNull(vo.getInputTokens(), "usage 缺失时 inputTokens 应为 null");
        assertNull(vo.getOutputTokens(), "usage 缺失时 outputTokens 应为 null");
    }

    // ==================== RAG 对话测试 ====================

    /** 构造测试检索块。 */
    private io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO searchVO(
            Long documentId, Long knowledgeBaseId, String fileName,
            int chunkIndex, String chunkText, Double score) {
        return io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO.of(
                documentId, knowledgeBaseId, fileName, chunkIndex, chunkText, score);
    }

    @Test
    void shouldRagChatWithHistoryAndCitations() {
        // 场景：会话内 RAG 正常链路——检索块 + 历史上下文 + AI 回答 + 引用落库
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "新对话"));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("根据资料[1]，缓存方案是 Redis", 10, 5));

        // 检索：返回 1 个相关块
        when(semanticSearchService.search("缓存方案是什么", 5))
                .thenReturn(List.of(searchVO(1L, 1L, "架构设计.md", 0, "系统使用 Redis 作为缓存", 0.85)));

        // 历史：一条用户消息 + 一条助手消息
        when(aiMessageMapper.selectList(any())).thenReturn(List.of(
                message(1L, "你好", AiMessageRole.USER, Instant.parse("2026-08-18T07:00:00Z")),
                message(2L, "你好！", AiMessageRole.ASSISTANT, Instant.parse("2026-08-18T07:01:00Z"))));

        io.github.qwertyhgb.knowflow.ai.vo.RagChatVO vo =
                chatService.ragChat(1L, 1L, "缓存方案是什么", 5, 0.3);

        // 1. AI 回答 + 引用数组
        assertEquals("根据资料[1]，缓存方案是 Redis", vo.getReply());
        assertEquals(1, vo.getCitations().size());
        assertEquals("架构设计.md", vo.getCitations().get(0).getFileName());
        assertEquals(0.85, vo.getCitations().get(0).getScore());

        // 2. 用户消息 + AI 消息落库（共 2 次 insert）
        ArgumentCaptor<AiMessage> messageCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper, times(2)).insert(messageCaptor.capture());
        AiMessage userMessage = messageCaptor.getAllValues().get(0);
        AiMessage aiMessage = messageCaptor.getAllValues().get(1);

        assertEquals(AiMessageRole.USER, userMessage.getRole());
        assertEquals("缓存方案是什么", userMessage.getContent());

        assertEquals(AiMessageRole.ASSISTANT, aiMessage.getRole());
        assertEquals("根据资料[1]，缓存方案是 Redis", aiMessage.getContent());
        // 引用 JSON 应非 null 且包含文件名
        assertTrue(aiMessage.getCitations().contains("架构设计.md"), "citations JSON 应包含引用来源");
        assertEquals(10, aiMessage.getInputTokens());
        assertEquals(5, aiMessage.getOutputTokens());

        // 3. Prompt 应包含历史 + 检索块编号 [1] + 问题
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("历史对话:"), "Prompt 应包含历史对话标记");
        assertTrue(prompt.contains("用户:你好"), "应包含历史用户消息");
        assertTrue(prompt.contains("助手:你好！"), "应包含历史助手消息");
        assertTrue(prompt.contains("参考资料:"), "Prompt 应包含参考资料标记");
        assertTrue(prompt.contains("[1] 来源:架构设计.md"), "参考资料应带编号与来源");
        assertTrue(prompt.contains("系统使用 Redis 作为缓存"), "参考资料应包含块文本");
        assertTrue(prompt.contains("用户问题:缓存方案是什么"), "Prompt 应包含当前问题");

        // 4. 标题自动生成（首条消息）
        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper, times(2)).updateById(conversationCaptor.capture());
        boolean titleUpdated = conversationCaptor.getAllValues().stream()
                .anyMatch(c -> "缓存方案是什么".equals(c.getTitle()));
        assertTrue(titleUpdated, "标题应被自动生成为首条消息");
    }

    @Test
    void shouldRagChatHandleNoRelevantBlocks() {
        // 场景：无相关块（低于阈值）→ 不调 LLM，返回提示文本，同时落库
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "新对话"));
        // 无相关块时不需要 ChatClient 真正调用，但方法开头仍会判空调用 getIfAvailable，
        // 故需 stub 它以返回 chatClient（不 stub 会因 strict 判定为"调用返回 null"走向 503 分支）
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);

        // 检索：返回 1 个块但 score 低于阈值 0.3
        when(semanticSearchService.search("不相关问题", 5))
                .thenReturn(List.of(searchVO(1L, 1L, "架构设计.md", 0, "某文本", 0.2)));

        io.github.qwertyhgb.knowflow.ai.vo.RagChatVO vo =
                chatService.ragChat(1L, 1L, "不相关问题", 5, 0.3);

        // 1. 返回提示文本 + 空引用
        assertEquals("抱歉，知识库中没有找到与您问题相关的内容，请换个问法或稍后再试", vo.getReply());
        assertEquals(0, vo.getCitations().size());

        // 2. 用户消息 + AI 提示消息落库（共 2 次 insert）
        ArgumentCaptor<AiMessage> messageCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper, times(2)).insert(messageCaptor.capture());
        AiMessage aiMessage = messageCaptor.getAllValues().get(1);
        assertEquals(AiMessageRole.ASSISTANT, aiMessage.getRole());
        assertTrue(aiMessage.getContent().contains("知识库中没有找到"), "AI 消息应为提示文本");
        assertEquals("[]", aiMessage.getCitations(), "无相关块时 citations 应为空数组 JSON");

        // 3. 不调用 LLM（prompt 没有被调用）
        verify(chatClient, never()).prompt();
    }

    @Test
    void shouldRagChatThrowNotFoundForOtherUsersConversation() {
        // 场景：他人会话 → 404，不落任何消息
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 999L, "别人的会话"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.ragChat(1L, 1L, "缓存方案是什么", 5, 0.3));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, exception.getErrorCode());
        verify(aiMessageMapper, never()).insert(any(AiMessage.class));
        verify(semanticSearchService, never()).search(anyString(), any(Integer.class));
    }

    @Test
    void shouldRagChatThrowServiceUnavailableWhenChatClientNotConfigured() {
        // 场景：ChatClient 未装配 → 503，不落消息
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "已有标题"));
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.ragChat(1L, 1L, "缓存方案是什么", 5, 0.3));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
        verify(aiMessageMapper, never()).insert(any(AiMessage.class));
    }

    @Test
    void shouldRagChatPersistUserMessageWhenLlmFails() {
        // 场景：LLM 调用失败 → 转 503，但用户消息已落库
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "新对话"));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenThrow(new RuntimeException("upstream timeout"));

        // 检索：返回 1 个相关块（触发 LLM 调用）
        when(semanticSearchService.search("缓存方案是什么", 5))
                .thenReturn(List.of(searchVO(1L, 1L, "架构设计.md", 0, "Redis 缓存", 0.85)));

        when(aiMessageMapper.selectList(any())).thenReturn(new ArrayList<>());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.ragChat(1L, 1L, "缓存方案是什么", 5, 0.3));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());

        // 用户消息已落库（一次 insert：用户消息），AI 消息未落库
        ArgumentCaptor<AiMessage> userCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper).insert(userCaptor.capture());
        assertEquals(AiMessageRole.USER, userCaptor.getValue().getRole());
        assertEquals("缓存方案是什么", userCaptor.getValue().getContent());
    }

    @Test
    void shouldRagChatStreamSendCitationsFirst() throws Exception {
        // 场景：流式 RAG——先发 citations 事件，再发回答分片，onComplete 时落库完整回答
        // 注意：本测试简化验证「citations 先发 + 消息落库」，流式分片的完整验证需要真实 Flux 订阅
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "新对话"));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);

        // 检索：返回 1 个相关块
        when(semanticSearchService.search("缓存方案是什么", 5))
                .thenReturn(List.of(searchVO(1L, 1L, "架构设计.md", 0, "系统使用 Redis 作为缓存", 0.85)));

        when(aiMessageMapper.selectList(any())).thenReturn(new ArrayList<>());

        // Mock SseEmitter（实际流式订阅在 Service 内异步进行，单元测试难以完整模拟，
        // 此处主要验证「检索 → citations 构造 → Service 不抛异常」的基础路径）
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);

        // Mock ChatClient 流式链（简化：不真实订阅 Flux，只验证构造路径）
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        org.springframework.ai.chat.client.ChatClient.StreamResponseSpec streamSpec =
                org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.StreamResponseSpec.class);
        when(promptSpec.stream()).thenReturn(streamSpec);
        reactor.core.publisher.Flux<String> emptyFlux = reactor.core.publisher.Flux.empty();
        when(streamSpec.content()).thenReturn(emptyFlux);

        // 调用流式接口（异步订阅在 Service 内进行，此处不会立即完成）
        chatService.ragChatStream(1L, 1L, "缓存方案是什么", 5, 0.3, emitter);

        // 短暂等待异步订阅完成（Flux.empty() 会立即触发 onComplete）
        Thread.sleep(100);

        // 验证：用户消息 + AI 消息落库（共 2 次）
        // onComplete 会在异步线程落库 AI 消息（空回答）
        ArgumentCaptor<AiMessage> messageCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper, times(2)).insert(messageCaptor.capture());
        assertEquals(AiMessageRole.USER, messageCaptor.getAllValues().get(0).getRole());
        assertEquals("缓存方案是什么", messageCaptor.getAllValues().get(0).getContent());
        assertEquals(AiMessageRole.ASSISTANT, messageCaptor.getAllValues().get(1).getRole());

        // 验证：citations 命名事件已发送（emitter.send 被调用，event 名为 citations）
        verify(emitter).send(any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
        // 验证：emitter.complete 被调用（流正常结束）
        verify(emitter).complete();
    }

    @Test
    void shouldRagChatStreamHandleNoRelevantBlocks() throws Exception {
        // 场景：流式 RAG 无相关块 → 发提示文本并 complete，同时落库
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, 1L, "新对话"));
        // 无相关块时仍需要 chatClientProvider（代码会调用 getIfAvailable）
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);

        // 检索：返回块但 score 低于阈值
        when(semanticSearchService.search("不相关问题", 5))
                .thenReturn(List.of(searchVO(1L, 1L, "架构设计.md", 0, "某文本", 0.2)));

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);

        chatService.ragChatStream(1L, 1L, "不相关问题", 5, 0.3, emitter);

        // 1. 用户消息 + AI 提示消息落库（共 2 次 insert）
        ArgumentCaptor<AiMessage> messageCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper, times(2)).insert(messageCaptor.capture());
        AiMessage aiMessage = messageCaptor.getAllValues().get(1);
        assertEquals(AiMessageRole.ASSISTANT, aiMessage.getRole());
        assertTrue(aiMessage.getContent().contains("知识库中没有找到"), "AI 消息应为提示文本");

        // 2. emitter.send 被调用（发提示文本）
        verify(emitter).send(any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
        // 3. emitter.complete 被调用
        verify(emitter).complete();

        // 4. 不调用 LLM（不会调用 prompt）
        verify(chatClient, never()).prompt();
    }

    // ==================== 限流测试 ====================

    @Test
    void shouldRejectChatWhenRateLimited() {
        // 场景：会话内对话被限流（tryAcquire 返回 false）→ 429。
        // 限流在归属校验之前执行：超限请求连数据库查询都不做，直接拒绝（省去无效 IO）。
        when(rateLimitService.tryAcquire(1L, "ai_conversation")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.chat(1L, 1L, "你好"));

        assertEquals(ErrorCode.RATE_LIMITED, exception.getErrorCode());
        // 限流拒绝时不应触发会话归属查询（skip bookkeeping for already-rejected requests）
        verify(conversationMapper, never()).selectById(any(Long.class));
        verify(aiMessageMapper, never()).insert(any(AiMessage.class));
    }

    @Test
    void shouldRejectRagChatWhenRateLimited() {
        // 场景：会话内 RAG 超限 → 429（RAG 同样调用付费 LLM，必须与普通对话共用配额）
        when(rateLimitService.tryAcquire(1L, "ai_conversation")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.ragChat(1L, 1L, "缓存方案是什么", 5, 0.3));

        assertEquals(ErrorCode.RATE_LIMITED, exception.getErrorCode());
        // 限流拒绝时不触发检索（不花 embedding 成本）
        verify(semanticSearchService, never()).search(anyString(), any(Integer.class));
        verify(aiMessageMapper, never()).insert(any(AiMessage.class));
    }

    @Test
    void shouldRejectRagChatStreamWhenRateLimited() {
        // 场景：会话内流式 RAG 超限 → 同步抛 429（尚未推流，SSE 头未发出）。
        // 注意：这里的 429 是「抛异常」而不是 error 事件——与归属于校验失败的处理不同，
        // 因为限流发生在所有异步/推流动作之前，异常仍能被全局异常处理转成 HTTP 状态码。
        when(rateLimitService.tryAcquire(1L, "ai_conversation")).thenReturn(false);

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.ragChatStream(1L, 1L, "缓存方案是什么", 5, 0.3, emitter));

        assertEquals(ErrorCode.RATE_LIMITED, exception.getErrorCode());
        // 限流拒绝时不落任何消息、不查询会话
        verify(aiMessageMapper, never()).insert(any(AiMessage.class));
        verify(conversationMapper, never()).selectById(any(Long.class));
    }

    @Test
    void shouldUseConversationActionKeyForRateLimit() {
        // 场景：会话内对话（chat/ragChat/ragChatStream 三者）共用 "ai_conversation" 配额，
        // 防止用户换个入口绕过限流
        when(rateLimitService.tryAcquire(1L, "ai_conversation")).thenReturn(false);

        assertThrows(BusinessException.class, () -> chatService.chat(1L, 1L, "你好"));
        verify(rateLimitService).tryAcquire(1L, "ai_conversation");
    }
}
