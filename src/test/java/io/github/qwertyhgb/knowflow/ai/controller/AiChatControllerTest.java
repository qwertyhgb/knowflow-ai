package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.service.AiChatService;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AiChatController} Web 层测试。
 *
 * <p>验证三个关键行为：
 * 1. 未登录访问返回 401（AI 能力不对匿名用户开放）；
 * 2. 已登录 + 合法入参返回 200，JSON 包含 reply；
 * 3. 已登录但 message 为空返回 400 参数校验错误（不发请求到 Service）。</p>
 *
 * <p>【不需要企业上下文？】AI 接口路径是 {@code /api/ai/chat}，不在
 * {@code /api/enterprises/{enterpriseId}} 企业作用域模式内，因此
 * 不经过 EnterpriseContextFilter 的企业成员校验，只需认证通过即可。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiChatService aiChatService;

    @MockitoBean
    private TokenService tokenService;

    /** 已认证的请求头（TokenService.resolveUserId 对该 token 返回用户 ID 即视为已登录）。 */
    private static final String AUTH_HEADER = "Bearer valid-token";

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        // 不带认证头 → 401，验证 AI 接口默认受 Security 保护
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "你好" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(aiChatService);
    }

    @Test
    void shouldReturnReplyForValidMessageWhenLoggedIn() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(aiChatService.chat("你好")).thenReturn("你好！我是 KnowFlow 智能助手。");

        mockMvc.perform(post("/api/ai/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "你好" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reply").value("你好！我是 KnowFlow 智能助手。"));
    }

    @Test
    void shouldRejectBlankMessage() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // message 为空白 → 参数校验失败 400，且不调用 Service
        mockMvc.perform(post("/api/ai/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "   " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(aiChatService);
    }

    @Test
    void shouldRejectMessageTooLong() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // message 超长（>2000 字符）→ 400，不调用 Service（防止超长输入拖垮模型调用）
        String tooLong = "A".repeat(2001);
        mockMvc.perform(post("/api/ai/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "%s" }
                                """.formatted(tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(aiChatService);
    }

    @Test
    void shouldReturnServiceUnavailableWhenAiCallThrowsBusinessException() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        // Service 抛 AI 服务不可用的业务异常（大模型调用失败的语义）
        when(aiChatService.chat(anyString()))
                .thenThrow(new io.github.qwertyhgb.knowflow.common.exception.BusinessException(
                        io.github.qwertyhgb.knowflow.common.exception.ErrorCode.AI_SERVICE_UNAVAILABLE));

        mockMvc.perform(post("/api/ai/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "你好" }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("AI 服务暂时不可用，请稍后重试"));
    }

    @Test
    void shouldStreamReplyForValidMessageWhenLoggedIn() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        // mock 的 chatStream 需要在后台线程异步完成 emitter，模拟真实的流式回调
        // （真实场景是 DeepSeek 网络流在 reactor 线程逐块回调）。
        // 若在 Controller 方法内同步 send + complete，SseEmitter 会在异步处理启动前就完成，
        // 破坏 Spring Security 的 WebAsyncManager 集成对认证上下文的传播，导致 asyncDispatch 403。
        doAnswer(invocation -> {
            SseEmitter emitter = invocation.getArgument(1);
            Thread thread = new Thread(() -> {
                try {
                    emitter.send(SseEmitter.event().data("你"));
                    emitter.send(SseEmitter.event().data("好"));
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            });
            thread.start();
            return null;
        }).when(aiChatService).chatStream(anyString(), any(SseEmitter.class));

        // SseEmitter 是异步返回值：perform 会先进入异步处理（asyncStarted），
        // 必须再用 asyncDispatch 拿到最终写出的 SSE 响应体。
        MvcResult mvcResult = mockMvc.perform(post("/api/ai/chat/stream")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "你好" }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult asyncResult = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/event-stream")))
                .andReturn();

        // SSE 响应体是 data: 前缀的事件流，断言包含 mock 出来的分片文本。
        // 注意：text/event-stream 未声明 charset，默认字符集解码会把中文变成乱码，
        // 必须显式用 UTF-8 解码（SseEmitter 以 UTF-8 写出响应字节）。
        String body = asyncResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("你"), "响应体应包含第一个分片");
        assertTrue(body.contains("好"), "响应体应包含第二个分片");
    }

    @Test
    void shouldReturnUnauthorizedForStreamWithoutToken() throws Exception {
        // 不带认证头 → 401，验证流式接口同样默认受 Security 保护
        mockMvc.perform(post("/api/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "你好" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(aiChatService);
    }

    @Test
    void shouldRejectBlankMessageForStream() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // message 为空白 → 参数校验失败 400，且不调用 Service（流式接口复用 AiChatRequest 的 @Valid 校验）
        mockMvc.perform(post("/api/ai/chat/stream")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "   " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(aiChatService);
    }
}
