package io.github.qwertyhgb.knowflow.ai.service.impl;

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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChatServiceImpl} 单元测试：纯 Mockito，不依赖真实 DeepSeek 网络调用。
 *
 * <p>测试核心：
 * 1. 正常路径——验证 ChatClient 链式调用被正确执行、系统提示词被设置、返回回答；
 * 2. 异常路径——底层抛 RuntimeException 时，Service 必须转成
 *    {@link ErrorCode#AI_SERVICE_UNAVAILABLE} 的 {@link BusinessException}（503 语义）；
 * 3. 未配置依赖——{@code ObjectProvider.getIfAvailable()} 返回 null 时同样抛 503
 *    （对应「未配置 DEEPSEEK_API_KEY 时调用应返回友好 503」的验收）。</p>
 *
 * <p>【为什么 mock 整条链式调用？】
 * ChatClient 是链式 API：{@code prompt().system().user().call().content()}。
 * 每一步都返回新的（或同一个）spec 对象，因此需要逐层用同名的 mock 把它们串起来，
 * 才能让 {@code call().content()} 返回我们预期的固定文本，否则会出现 NPE。</p>
 */
@ExtendWith(MockitoExtension.class)
class AiChatServiceImplTest {

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

    private AiChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        chatService = new AiChatServiceImpl(chatClientProvider);
    }

    @Test
    void shouldReturnReplyOnSuccessfulCall() {
        // 场景：ChatClient 已装配（配置了 api-key），getIfAvailable() 返回 mock chatClient
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        String fixedReply = "这是 DeepSeek 的回答";
        when(chatClient.prompt()).thenReturn(promptSpec);
        // system()/user() 返回同一 ChatClientRequestSpec，call() 返回 CallResponseSpec
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(fixedReply);

        String reply = chatService.chat("你好");

        assertEquals(fixedReply, reply);
        // 验证系统提示词确实被设置了（人设行为准则的关键参数）
        verify(promptSpec).system(org.mockito.ArgumentMatchers.contains("KnowFlow"));
        verify(promptSpec).user("你好");
    }

    @Test
    void shouldThrowServiceUnavailableWhenUnderlyingCallFails() {
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        // 底层 content() 抛 RuntimeException（模拟网络/限流/超时）
        when(callResponseSpec.content()).thenThrow(new RuntimeException("connection timeout"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.chat("你好"));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void shouldThrowServiceUnavailableWhenPromptThrows() {
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        // 另一种失败形态：prompt() 直接就抛异常（如构造请求失败）
        when(chatClient.prompt()).thenThrow(new IllegalStateException("bad state"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.chat("你好"));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void shouldThrowServiceUnavailableWhenChatClientNotConfigured() {
        // 场景：未配置 DEEPSEEK_API_KEY → ChatClient 未装配 → getIfAvailable() 返回 null。
        // 此时调用应返回 503 友好错误，而不是崩溃或 500。
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatService.chat("你好"));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void shouldStreamChunksAndComplete() throws Exception {
        // 场景：ChatClient 已装配，stream() 返回三个分片。
        // 关键：stream() 返回 StreamResponseSpec（与 call() 返回 CallResponseSpec 是不同 spec），
        // 其 content() 返回 Flux<String>；这里用 Flux.just 模拟三个 token 分片。
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("你", "好", "!"));

        // 用 spy 拦截 SseEmitter：
        // 纯单元测试里 SseEmitter 没有真实 HTTP handler，complete 不会触发 onCompletion 回调，
        // 因此用 spy 拦截 complete 作为「流已结束」的信号；send 的调用次数与内容用 ArgumentCaptor 验证。
        SseEmitter emitter = spy(new SseEmitter(120_000L));
        CountDownLatch completionLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            completionLatch.countDown();
            return null;
        }).when(emitter).complete();

        chatService.chatStream("你好", emitter);

        // 响应式回调可能是异步的：必须等待 complete 信号，否则断言时数据可能还没到。
        assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "流应在超时时间内完成");
        // 断言 send 被调用 3 次（三个分片），并验证发送内容包含全部分片。
        // 注意：SseEventBuilder.build() 返回的是已格式化的 SSE 文本（含 "data:" 前缀与换行），
        // 因此用「拼接后包含子串」断言，而不是精确等于原始分片。
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(3)).send(captor.capture());
        String sentText = captor.getAllValues().stream()
                .flatMap(builder -> builder.build().stream())
                .map(d -> String.valueOf(d.getData()))
                .collect(Collectors.joining());
        assertTrue(sentText.contains("你"), "应包含第一个分片");
        assertTrue(sentText.contains("好"), "应包含第二个分片");
        assertTrue(sentText.contains("!"), "应包含第三个分片");
        // 正常路径不应出现错误收尾
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void shouldSendErrorEventWhenChatClientNotConfigured() throws Exception {
        // 场景：未配置 DEEPSEEK_API_KEY → getIfAvailable() 返回 null。
        // 流式接口不能抛 503，而是通过 error 事件 + complete 通知客户端。
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        SseEmitter emitter = spy(new SseEmitter(120_000L));
        CountDownLatch completionLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            completionLatch.countDown();
            return null;
        }).when(emitter).complete();

        chatService.chatStream("你好", emitter);

        assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "流应在超时时间内完成");
        // 唯一发送的事件是 error 事件，断言其 data 包含 AI 不可用文案（与同步接口 503 语义一致）
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        String sentText = captor.getValue().build().stream()
                .map(d -> String.valueOf(d.getData()))
                .collect(Collectors.joining());
        assertTrue(sentText.contains(ErrorCode.AI_SERVICE_UNAVAILABLE.getMessage()), "应包含 AI 不可用文案");
    }

    @Test
    void shouldSendErrorEventOnStreamFailure() throws Exception {
        // 场景：流中途失败（如网络异常），stream().content() 返回 Flux.error。
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.error(new RuntimeException("upstream failure")));

        SseEmitter emitter = spy(new SseEmitter(120_000L));
        CountDownLatch completionLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            completionLatch.countDown();
            return null;
        }).when(emitter).complete();

        chatService.chatStream("你好", emitter);

        assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "流应在超时时间内完成");
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        String sentText = captor.getValue().build().stream()
                .map(d -> String.valueOf(d.getData()))
                .collect(Collectors.joining());
        assertTrue(sentText.contains(ErrorCode.AI_SERVICE_UNAVAILABLE.getMessage()), "应包含 AI 不可用文案");
    }
}
