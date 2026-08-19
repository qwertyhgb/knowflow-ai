package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.service.RagChatService;
import io.github.qwertyhgb.knowflow.ai.vo.RagChatVO;
import io.github.qwertyhgb.knowflow.ai.vo.RagCitationVO;
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
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * {@link RagChatController} Web 层测试。
 *
 * <p>验证五个关键行为：
 * 1. 未登录访问返回 401（RAG 能力不对匿名用户开放）；
 * 2. 已登录 + 合法入参返回 200，reply/citations 结构正确；
 * 3. question 为空返回 400 参数校验错误；
 * 4. topK 越界（0 / 21）返回 400；
 * 5. scoreThreshold 越界（1.5）返回 400。</p>
 *
 * <p><strong>为什么需要 @MockitoBean 注入 TokenService？</strong>
 * 与 AiChatControllerTest / SemanticSearchControllerTest 相同：认证过滤器依赖
 * TokenService 解析 Bearer Token，mock 后对 valid-token 返回固定用户 ID 即视为已登录。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RagChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagChatService ragChatService;

    @MockitoBean
    private TokenService tokenService;

    /** 已认证的请求头（TokenService.resolveUserId 对该 token 返回用户 ID 即视为已登录）。 */
    private static final String AUTH_HEADER = "Bearer valid-token";

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        // 不带认证头 → 401，验证 RAG 对话接口默认受 Security 保护
        mockMvc.perform(post("/api/ai/rag/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "如何提升系统查询速度" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(ragChatService);
    }

    @Test
    void shouldReturnReplyWithCitationsForValidRequest() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(ragChatService.chat(anyLong(), anyString(), anyInt(), anyDouble())).thenReturn(RagChatVO.of(
                "根据资料[1]，Redis 缓存能显著降低数据库查询压力。",
                List.of(RagCitationVO.of(1L, 2L, "缓存设计.md", 0,
                        "Redis 缓存可以显著降低数据库查询压力", 0.8))));

        mockMvc.perform(post("/api/ai/rag/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "如何提升系统查询速度" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reply").value("根据资料[1]，Redis 缓存能显著降低数据库查询压力。"))
                .andExpect(jsonPath("$.data.citations.length()").value(1))
                .andExpect(jsonPath("$.data.citations[0].documentId").value(1))
                .andExpect(jsonPath("$.data.citations[0].knowledgeBaseId").value(2))
                .andExpect(jsonPath("$.data.citations[0].fileName").value("缓存设计.md"))
                .andExpect(jsonPath("$.data.citations[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.data.citations[0].chunkText")
                        .value("Redis 缓存可以显著降低数据库查询压力"))
                .andExpect(jsonPath("$.data.citations[0].score").value(0.8));
    }

    @Test
    void shouldRejectBlankQuestion() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // question 为空白 → 参数校验失败 400，且不调用 Service
        mockMvc.perform(post("/api/ai/rag/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "   " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(ragChatService);
    }

    @Test
    void shouldRejectTopKBelowRange() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // topK = 0 → 400（@Min(1) 拦截）
        mockMvc.perform(post("/api/ai/rag/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "问题", "topK": 0 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(ragChatService);
    }

    @Test
    void shouldRejectTopKAboveRange() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // topK = 21 → 400（@Max(20) 拦截）
        mockMvc.perform(post("/api/ai/rag/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "问题", "topK": 21 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(ragChatService);
    }

    @Test
    void shouldRejectScoreThresholdAboveRange() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // scoreThreshold = 1.5 → 400（@DecimalMax("1.0") 拦截）
        mockMvc.perform(post("/api/ai/rag/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "问题", "scoreThreshold": 1.5 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(ragChatService);
    }

    @Test
    void shouldRejectScoreThresholdBelowRange() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // scoreThreshold = -0.1 → 400（@DecimalMin("0.0") 拦截）
        mockMvc.perform(post("/api/ai/rag/chat")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "问题", "scoreThreshold": -0.1 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(ragChatService);
    }

    // ==================== 流式 RAG（/chat/stream）====================

    @Test
    void shouldStreamRagWhenLoggedIn() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        // mock 的 chatStream 需要在后台线程异步完成 emitter（发送 citations + 回答分片后结束），
        // 模拟真实的流式回调。若在 Controller 方法内同步完成，会破坏 Security 异步上下文传播
        // 导致 asyncDispatch 403（Phase 9 已验证：后台线程异步是正确做法）。
        doAnswer(invocation -> {
            // chatStream(Long userId, String question, int topK, double threshold, SseEmitter emitter)
            // —— emitter 是第 5 个参数
            SseEmitter emitter = invocation.getArgument(4);
            Thread thread = new Thread(() -> {
                try {
                    emitter.send(SseEmitter.event().name("citations").data("[{\"documentId\":1}]"));
                    emitter.send(SseEmitter.event().data("你"));
                    emitter.send(SseEmitter.event().data("好"));
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            });
            thread.start();
            return null;
        }).when(ragChatService).chatStream(anyLong(), anyString(), anyInt(), anyDouble(), any(SseEmitter.class));

        // SseEmitter 是异步返回值：perform 先进入异步处理（asyncStarted），
        // 再用 asyncDispatch 拿到最终写出的 SSE 响应体。
        MvcResult mvcResult = mockMvc.perform(post("/api/ai/rag/chat/stream")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "如何提升系统查询速度" }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult asyncResult = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/event-stream")))
                .andReturn();

        // SSE 响应体是事件流：断言含 citations 命名事件与回答分片。
        // 注意 text/event-stream 未声明 charset，必须用 UTF-8 显式解码中文分片。
        String body = asyncResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:citations"),
                "响应体应包含 citations 命名事件");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("你"), "响应体应包含第一个分片");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("好"), "响应体应包含第二个分片");
    }

    @Test
    void shouldReturnUnauthorizedForStreamWithoutToken() throws Exception {
        // 不带认证头 → 401，验证流式 RAG 接口同样默认受 Security 保护
        mockMvc.perform(post("/api/ai/rag/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "问题" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(ragChatService);
    }
}
