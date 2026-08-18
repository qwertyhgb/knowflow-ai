package io.github.qwertyhgb.knowflow.ai.service.impl;

import io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService;
import io.github.qwertyhgb.knowflow.ai.vo.RagChatVO;
import io.github.qwertyhgb.knowflow.ai.vo.RagCitationVO;
import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagChatServiceImpl} 单元测试：纯 Mockito，不依赖真实 DeepSeek / 硅基流动网络调用。
 *
 * <p>测试核心：
 * 1. 正常链路——检索 3 块，阈值过滤后剩 2 块，LLM 收到的 user 消息含 [1][2]、不含第 3 块，
 *    回答与 citations 正确；
 * 2. 全部低于阈值——返回友好提示、citations 为空、ChatClient 不被调用（省一次 LLM 成本）；
 * 3. LLM 抛异常 → 转 503；
 * 4. ChatClient 未装配 → 503；
 * 5. score 为 null 的块被保留（默认保留策略）。</p>
 *
 * <p><strong>为什么 mock 整条 ChatClient 链？</strong>
 * ChatClient 是链式 API：{@code prompt().system().user().call().content()}。
 * 每步返回（或复用）spec 对象，需逐层 mock 串起来才能让 {@code content()} 返回预期文本
 * （与 AiChatServiceImplTest 完全一致的模式）。</p>
 */
@ExtendWith(MockitoExtension.class)
class RagChatServiceImplTest {

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
    private ChatClient.StreamResponseSpec streamResponseSpec;

    @Mock
    private JsonMapper jsonMapper;

    private RagChatServiceImpl ragChatService;

    @BeforeEach
    void setUp() {
        ragChatService = new RagChatServiceImpl(semanticSearchService, chatClientProvider, jsonMapper);
    }

    /** 构造一个测试检索块。 */
    private SemanticSearchVO block(long documentId, int chunkIndex, String text, Double score) {
        return SemanticSearchVO.of(documentId, 2L, "缓存设计.md", chunkIndex, text, score);
    }

    @Test
    void shouldAnswerWithCitationsOnNormalFlow() {
        // 场景：检索 3 块，分数 0.8 / 0.5 / 0.2，阈值 0.3 → 保留前 2 块，丢弃第 3 块
        when(semanticSearchService.search("如何提升系统查询速度", 5)).thenReturn(List.of(
                block(1L, 0, "Redis 缓存可以显著降低数据库查询压力", 0.8),
                block(1L, 1, "使用多级缓存进一步减少热点数据穿透", 0.5),
                block(1L, 2, "低相关噪声块", 0.2)));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("根据资料[1]，Redis 缓存能显著降低数据库查询压力。");

        RagChatVO vo = ragChatService.chat("如何提升系统查询速度", 5, 0.3);

        // 回答原样透传
        assertEquals("根据资料[1]，Redis 缓存能显著降低数据库查询压力。", vo.getReply());

        // citations 为过滤后的 2 块，字段映射正确
        List<RagCitationVO> citations = vo.getCitations();
        assertEquals(2, citations.size());
        assertEquals(1L, citations.get(0).getDocumentId());
        assertEquals(2L, citations.get(0).getKnowledgeBaseId());
        assertEquals("缓存设计.md", citations.get(0).getFileName());
        assertEquals(0, citations.get(0).getChunkIndex());
        assertEquals("Redis 缓存可以显著降低数据库查询压力", citations.get(0).getChunkText());
        assertEquals(0.8, citations.get(0).getScore());
        assertEquals(1, citations.get(1).getChunkIndex());
        assertEquals(0.5, citations.get(1).getScore());

        // 关键断言：LLM 收到的 user 消息包含 [1][2] 两块内容、不含被过滤的第 3 块
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec).user(userCaptor.capture());
        String userMessage = userCaptor.getValue();
        assertTrue(userMessage.contains("[1] 来源:缓存设计.md 第0块"), "user 消息应含编号[1]的块");
        assertTrue(userMessage.contains("[2] 来源:缓存设计.md 第1块"), "user 消息应含编号[2]的块");
        assertTrue(userMessage.contains("Redis 缓存可以显著降低数据库查询压力"), "应含第1块内容");
        assertTrue(userMessage.contains("使用多级缓存进一步减少热点数据穿透"), "应含第2块内容");
        assertTrue(!userMessage.contains("低相关噪声块"), "被阈值过滤的块不应拼进上下文");
    }

    @Test
    void shouldReturnNoContentReplyWhenAllFiltered() {
        // 场景：检索 3 块，全部低于阈值 0.7 → 无可用资料，不调用 LLM
        when(semanticSearchService.search("问题", 5)).thenReturn(List.of(
                block(1L, 0, "块A", 0.3),
                block(1L, 1, "块B", 0.2),
                block(1L, 2, "块C", 0.1)));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);

        RagChatVO vo = ragChatService.chat("问题", 5, 0.7);

        // 返回友好提示 + 空引用，且 LLM 不被调用（省成本 + 不编造）
        assertTrue(vo.getReply().contains("没有找到与您问题相关的内容"));
        assertTrue(vo.getCitations().isEmpty());
        verify(chatClient, never()).prompt();
    }

    @Test
    void shouldThrowServiceUnavailableWhenLlmFails() {
        // 场景：LLM 调用抛 RuntimeException（网络/限流）→ 转 503 结构化错误
        when(semanticSearchService.search("问题", 5)).thenReturn(List.of(
                block(1L, 0, "相关块", 0.9)));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenThrow(new RuntimeException("upstream timeout"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ragChatService.chat("问题", 5, 0.3));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void shouldThrowServiceUnavailableWhenChatClientNotConfigured() {
        // 场景：未配置 DEEPSEEK_API_KEY → ChatClient 未装配 → 503（不触发检索）
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ragChatService.chat("问题", 5, 0.3));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
        // 判空前置：LLM 不可用时不浪费一次检索调用
        verify(semanticSearchService, never()).search(anyString(), anyInt());
    }

    @Test
    void shouldKeepNullScoreBlocks() {
        // 场景：块 score 为 null（存储实现不返回分数）→ 默认保留（宁可保留不可误杀）
        when(semanticSearchService.search("问题", 5)).thenReturn(List.of(
                block(1L, 0, "无分数但相关", null)));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("基于[1]回答");

        RagChatVO vo = ragChatService.chat("问题", 5, 0.3);

        // null 分数块被保留：citations 有 1 条且 score 为 null
        assertEquals(1, vo.getCitations().size());
        assertEquals(null, vo.getCitations().get(0).getScore());
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptSpec).user(userCaptor.capture());
        assertTrue(userCaptor.getValue().contains("无分数但相关"), "null 分数块应保留在上下文中");
    }

    // ==================== 流式 RAG（chatStream）====================

    /**
     * 拦截 SseEmitter 的 send 调用，把每个事件的 SSE 文本收集到 events 列表。
     *
     * <p>【为什么用 spy + doAnswer？】纯单元测试里 SseEmitter 没有真实 HTTP handler：
     * send 的数据不会真正写出、complete 也不会触发 onCompletion 回调。用 spy 拦截
     * send/complete，手动收集事件文本（SseEventBuilder.build() 返回的 data 是含
     * "event:" 行的完整 SSE 帧文本）+ 用 CountDownLatch 等待 complete 信号——
     * 响应式回调是异步的，必须等待，否则断言时数据还没到（与 AiChatServiceImplTest
     * 的流式测试模式完全一致）。</p>
     */
    private SseEmitter spyEmitter(List<String> events, CountDownLatch completionLatch) throws Exception {
        SseEmitter emitter = spy(new SseEmitter(120_000L));
        doAnswer(invocation -> {
            SseEmitter.SseEventBuilder builder = invocation.getArgument(0);
            // builder.build() 返回的 data 是完整 SSE 帧文本（含 event: 行与 data: 行）
            builder.build().forEach(d -> events.add(String.valueOf(d.getData())));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        doAnswer(invocation -> {
            completionLatch.countDown();
            return null;
        }).when(emitter).complete();
        return emitter;
    }

    @Test
    void shouldStreamCitationsThenChunksAndComplete() throws Exception {
        // 场景：检索 2 个高分块（都高于阈值 0.3），citations 序列化 + 流式回答分片
        when(semanticSearchService.search("问题", 5)).thenReturn(List.of(
                block(1L, 0, "Redis 缓存可以显著降低数据库查询压力", 0.8),
                block(1L, 1, "使用多级缓存进一步减少热点数据穿透", 0.5)));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("你", "好"));
        // mock citations 的 JSON 序列化（JsonMapper 是 mock，不会真序列化）
        when(jsonMapper.writeValueAsString(anyList())).thenReturn("[{\"documentId\":1}]");

        List<String> events = new java.util.ArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(1);
        SseEmitter emitter = spyEmitter(events, completionLatch);

        ragChatService.chatStream("问题", 5, 0.3, emitter);

        // 等待流完成：citations 事件 + 2 个回答分片 + complete
        assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "流应在超时时间内完成");
        // 3 次 send：1 次 citations 命名事件 + 2 次 data 分片
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(3)).send(captor.capture());

        // 【为什么不能按 index 断言单个事件？】SseEventBuilder.build() 返回的 Set 里，
        // 每个事件会拆成多个 DataWithMediaType（"data:" 前缀、值、结尾换行各一个），
        // 因此必须把所有片段拼接成完整文本后再断言包含关系。
        String sentText = String.join("", events);
        assertTrue(sentText.contains("event:citations"), "应包含 citations 命名事件");
        assertTrue(sentText.contains("[{\"documentId\":1}]"), "citations 事件应携带 JSON 内容");
        // 回答分片（默认 data 事件），内容为分片文本
        assertTrue(sentText.contains("你"), "应包含第一个回答分片");
        assertTrue(sentText.contains("好"), "应包含第二个回答分片");
        // 正常路径不应出现错误收尾
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void shouldStreamNoContentReplyWhenAllFiltered() throws Exception {
        // 场景：检索到的块全部低于阈值 → 空上下文，直接发提示文本并结束，不调用 LLM
        when(semanticSearchService.search("问题", 5)).thenReturn(List.of(
                block(1L, 0, "块A", 0.3),
                block(1L, 1, "块B", 0.2)));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);

        List<String> events = new java.util.ArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(1);
        SseEmitter emitter = spyEmitter(events, completionLatch);

        ragChatService.chatStream("问题", 5, 0.7, emitter);

        assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "流应在超时时间内完成");
        // 只发了一条 data 提示文本
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        assertTrue(String.join("", events).contains("没有找到与您问题相关的内容"), "应发送友好提示文本");
        // 空上下文不调用 LLM（省成本 + 不编造）
        verify(chatClient, never()).prompt();
    }

    @Test
    void shouldSendErrorEventWhenChatClientNotConfigured() throws Exception {
        // 场景：未配置 DEEPSEEK_API_KEY → ChatClient 未装配 → 发 error 事件而非 503
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        List<String> events = new java.util.ArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(1);
        SseEmitter emitter = spyEmitter(events, completionLatch);

        ragChatService.chatStream("问题", 5, 0.3, emitter);

        assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "流应在超时时间内完成");
        // 只发了一条 error 事件
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        String sentText = String.join("", events);
        assertTrue(sentText.contains("event:error"), "应发送 error 命名事件");
        assertTrue(sentText.contains(ErrorCode.AI_SERVICE_UNAVAILABLE.getMessage()),
                "error 事件应携带 AI 不可用文案");
    }

    @Test
    void shouldSendErrorEventOnStreamFailure() throws Exception {
        // 场景：citations 正常发出，但 LLM 流中途失败（Flux.error）
        when(semanticSearchService.search("问题", 5)).thenReturn(List.of(
                block(1L, 0, "相关块", 0.9)));
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.error(new RuntimeException("upstream failure")));
        when(jsonMapper.writeValueAsString(anyList())).thenReturn("[{\"documentId\":1}]");

        List<String> events = new java.util.ArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(1);
        SseEmitter emitter = spyEmitter(events, completionLatch);

        ragChatService.chatStream("问题", 5, 0.3, emitter);

        assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "流应在超时时间内完成");
        // 2 次 send：1 次 citations + 1 次 error 事件
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(captor.capture());
        String sentText = String.join("", events);
        assertTrue(sentText.contains("event:citations"), "应先发送 citations");
        assertTrue(sentText.contains("event:error"), "流失败后应发送 error 事件");
        assertTrue(sentText.contains(ErrorCode.AI_SERVICE_UNAVAILABLE.getMessage()),
                "error 事件应携带 AI 不可用文案");
    }
}
