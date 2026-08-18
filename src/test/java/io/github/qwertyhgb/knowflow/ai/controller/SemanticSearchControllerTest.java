package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService;
import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SemanticSearchController} Web 层测试。
 *
 * <p>验证四个关键行为：
 * 1. 未登录访问返回 401（语义搜索不对匿名用户开放）；
 * 2. 已登录 + 合法入参返回 200，JSON 结构（数组 + 字段）正确；
 * 3. question 为空返回 400 参数校验错误；
 * 4. topK 越界（0 / 21）返回 400。</p>
 *
 * <p><strong>为什么需要 @MockitoBean 注入 TokenService？</strong>
 * 与 AiChatControllerTest / DocumentVectorizeControllerTest 相同：认证过滤器依赖
 * TokenService 解析 Bearer Token，mock 后对 valid-token 返回固定用户 ID 即视为已登录。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SemanticSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SemanticSearchService semanticSearchService;

    @MockitoBean
    private TokenService tokenService;

    /** 已认证的请求头（TokenService.resolveUserId 对该 token 返回用户 ID 即视为已登录）。 */
    private static final String AUTH_HEADER = "Bearer valid-token";

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        // 不带认证头 → 401，验证语义搜索接口默认受 Security 保护
        mockMvc.perform(post("/api/ai/search/semantic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "如何提升系统查询速度", "topK": 3 }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(semanticSearchService);
    }

    @Test
    void shouldReturnTopKChunksForValidRequest() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(semanticSearchService.search(eq("如何提升系统查询速度"), eq(3))).thenReturn(List.of(
                SemanticSearchVO.of(1L, 2L, "缓存设计.md", 0,
                        "Redis 缓存可以显著降低数据库查询压力", 0.87),
                SemanticSearchVO.of(1L, 2L, "缓存设计.md", 1,
                        "使用多级缓存进一步减少热点数据穿透", 0.75)));

        mockMvc.perform(post("/api/ai/search/semantic")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "如何提升系统查询速度", "topK": 3 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].documentId").value(1))
                .andExpect(jsonPath("$.data[0].knowledgeBaseId").value(2))
                .andExpect(jsonPath("$.data[0].fileName").value("缓存设计.md"))
                .andExpect(jsonPath("$.data[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.data[0].chunkText").value("Redis 缓存可以显著降低数据库查询压力"))
                .andExpect(jsonPath("$.data[0].score").value(0.87));
    }

    @Test
    void shouldUseDefaultTopKWhenOmitted() throws Exception {
        // 场景：topK 缺省 → 走默认值 5（resolveTopK），Service 收到 5
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(semanticSearchService.search(eq("问题"), eq(5))).thenReturn(List.of());

        mockMvc.perform(post("/api/ai/search/semantic")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "问题" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void shouldRejectBlankQuestion() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // question 为空白 → 参数校验失败 400，且不调用 Service
        mockMvc.perform(post("/api/ai/search/semantic")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "   " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(semanticSearchService);
    }

    @Test
    void shouldRejectTopKBelowRange() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // topK = 0 → 400（@Min(1) 拦截）
        mockMvc.perform(post("/api/ai/search/semantic")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "问题", "topK": 0 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(semanticSearchService);
    }

    @Test
    void shouldRejectTopKAboveRange() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // topK = 21 → 400（@Max(20) 拦截）
        mockMvc.perform(post("/api/ai/search/semantic")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "问题", "topK": 21 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(semanticSearchService);
    }
}
