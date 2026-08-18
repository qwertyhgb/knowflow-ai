package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.entity.AiMessage;
import io.github.qwertyhgb.knowflow.ai.entity.Conversation;
import io.github.qwertyhgb.knowflow.ai.enums.AiMessageRole;
import io.github.qwertyhgb.knowflow.ai.service.ConversationChatService;
import io.github.qwertyhgb.knowflow.ai.service.ConversationService;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationChatVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationDetailVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationMessageVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationVO;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ConversationController} Web 层测试。
 *
 * <p>验证七个关键行为：
 * 1. 未登录访问返回 401（会话能力不对匿名用户开放）；
 * 2. 创建会话成功 200；
 * 3. 列表成功 200；
 * 4. 详情成功 200 / 他人会话 404；
 * 5. 删除成功 200；
 * 6. 发送消息成功 200 / 空消息 400。</p>
 *
 * <p><strong>为什么需要 @MockitoBean 注入 TokenService？</strong>
 * 与 AiChatControllerTest 等相同：认证过滤器依赖 TokenService 解析 Bearer Token，
 * mock 后对 valid-token 返回固定用户 ID 即视为已登录。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService conversationService;

    @MockitoBean
    private ConversationChatService conversationChatService;

    @MockitoBean
    private TokenService tokenService;

    /** 已认证的请求头（TokenService.resolveUserId 对该 token 返回用户 ID 即视为已登录）。 */
    private static final String AUTH_HEADER = "Bearer valid-token";

    private static final Instant FIXED_TIME = Instant.parse("2026-08-18T08:00:00Z");

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        // 不带认证头 → 401，验证会话接口默认受 Security 保护
        mockMvc.perform(post("/api/ai/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(conversationService);
    }

    @Test
    void shouldCreateConversation() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(conversationService.createConversation(1L))
                .thenReturn(ConversationVO.from(conversation(100L, "新对话")));

        mockMvc.perform(post("/api/ai/conversations")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.title").value("新对话"));
    }

    @Test
    void shouldListConversations() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(conversationService.listMyConversations(1L))
                .thenReturn(List.of(ConversationVO.from(conversation(1L, "第一个会话"))));

        mockMvc.perform(get("/api/ai/conversations")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].title").value("第一个会话"));
    }

    @Test
    void shouldGetConversationDetail() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        AiMessage userMessage = new AiMessage();
        userMessage.setId(1L);
        userMessage.setConversationId(1L);
        userMessage.setRole(AiMessageRole.USER);
        userMessage.setContent("你好");
        userMessage.setCreatedAt(FIXED_TIME);
        when(conversationService.getConversationDetail(1L, 1L))
                .thenReturn(ConversationDetailVO.of(1L, "会话标题", FIXED_TIME, FIXED_TIME,
                        List.of(ConversationMessageVO.from(userMessage))));

        mockMvc.perform(get("/api/ai/conversations/1")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("会话标题"))
                .andExpect(jsonPath("$.data.messages.length()").value(1))
                .andExpect(jsonPath("$.data.messages[0].content").value("你好"));
    }

    @Test
    void shouldReturnNotFoundForOtherUsersConversation() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(conversationService.getConversationDetail(1L, 99L))
                .thenThrow(new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND));

        mockMvc.perform(get("/api/ai/conversations/99")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void shouldDeleteConversation() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(delete("/api/ai/conversations/1")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void shouldChatInConversation() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(conversationChatService.chat(1L, 1L, "你好"))
                .thenReturn(ConversationChatVO.of("你好！我是智能助手。", 10, 5));

        mockMvc.perform(post("/api/ai/conversations/1/messages")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "你好" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reply").value("你好！我是智能助手。"))
                .andExpect(jsonPath("$.data.inputTokens").value(10))
                .andExpect(jsonPath("$.data.outputTokens").value(5));
    }

    @Test
    void shouldRejectBlankMessage() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // message 为空白 → 参数校验失败 400，且不调用 Service
        mockMvc.perform(post("/api/ai/conversations/1/messages")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "   " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(conversationChatService);
    }

    @Test
    void shouldReturnNotFoundWhenChatInOtherUsersConversation() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(conversationChatService.chat(eq(1L), eq(99L), anyString()))
                .thenThrow(new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND));

        mockMvc.perform(post("/api/ai/conversations/99/messages")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "你好" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    // ==================== RAG 对话测试 ====================

    @Test
    void shouldRagChatInConversation() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        io.github.qwertyhgb.knowflow.ai.vo.RagCitationVO citation =
                io.github.qwertyhgb.knowflow.ai.vo.RagCitationVO.of(
                        1L, 1L, "架构设计.md", 0, "系统使用 Redis 作为缓存", 0.85);
        when(conversationChatService.ragChat(1L, 1L, "缓存方案是什么", 5, 0.3))
                .thenReturn(io.github.qwertyhgb.knowflow.ai.vo.RagChatVO.of(
                        "根据资料[1]，缓存方案是 Redis", List.of(citation)));

        mockMvc.perform(post("/api/ai/conversations/1/messages/rag")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "缓存方案是什么", "topK": 5, "scoreThreshold": 0.3 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reply").value("根据资料[1]，缓存方案是 Redis"))
                .andExpect(jsonPath("$.data.citations.length()").value(1))
                .andExpect(jsonPath("$.data.citations[0].fileName").value("架构设计.md"))
                .andExpect(jsonPath("$.data.citations[0].score").value(0.85));
    }

    @Test
    void shouldRagChatApplyDefaultValues() throws Exception {
        // question 必填，topK/scoreThreshold 可空 → Controller 应用默认值 5/0.3
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(conversationChatService.ragChat(1L, 1L, "缓存方案是什么", 5, 0.3))
                .thenReturn(io.github.qwertyhgb.knowflow.ai.vo.RagChatVO.of("回答", List.of()));

        mockMvc.perform(post("/api/ai/conversations/1/messages/rag")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "缓存方案是什么" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void shouldRejectBlankQuestionInRagChat() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // question 为空白 → 参数校验失败 400
        mockMvc.perform(post("/api/ai/conversations/1/messages/rag")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "   " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(conversationChatService);
    }

    @Test
    void shouldRejectInvalidTopKInRagChat() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // topK = 0（小于 1）→ 参数校验失败 400
        mockMvc.perform(post("/api/ai/conversations/1/messages/rag")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "缓存方案是什么", "topK": 0 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(conversationChatService);
    }

    @Test
    void shouldReturnUnauthorizedForRagChatWithoutToken() throws Exception {
        // 不带认证头 → 401
        mockMvc.perform(post("/api/ai/conversations/1/messages/rag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "缓存方案是什么" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(conversationChatService);
    }

    @Test
    void shouldRagChatStreamReturnSseContentType() throws Exception {
        // 流式 RAG：验证 Content-Type 为 text/event-stream
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(post("/api/ai/conversations/1/messages/rag/stream")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "缓存方案是什么" }
                                """))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String contentType = result.getResponse().getContentType();
                    assertTrue(contentType != null && contentType.contains("text/event-stream"),
                            "流式接口应返回 text/event-stream Content-Type");
                });
    }

    @Test
    void shouldReturnUnauthorizedForRagChatStreamWithoutToken() throws Exception {
        // 流式 RAG 不带认证头 → 401
        mockMvc.perform(post("/api/ai/conversations/1/messages/rag/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "缓存方案是什么" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(conversationChatService);
    }

    /** 构造测试会话实体。 */
    private Conversation conversation(Long id, String title) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserId(1L);
        conversation.setTitle(title);
        conversation.setCreatedAt(FIXED_TIME);
        conversation.setUpdatedAt(FIXED_TIME);
        return conversation;
    }

    /** 断言辅助：assertTrue（JUnit 5 不自动导入，需显式 import 或内联实现）。 */
    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
