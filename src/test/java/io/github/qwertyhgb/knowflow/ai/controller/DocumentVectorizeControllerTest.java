package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.service.DocumentVectorizeService;
import io.github.qwertyhgb.knowflow.ai.vo.DocumentVectorizeVO;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DocumentVectorizeController} Web 层测试。
 *
 * <p>验证三个关键行为：
 * 1. 未登录访问返回 401（向量化能力不对匿名用户开放）；
 * 2. 已登录 + 合法 documentId 返回 200，data 含 chunkCount；
 * 3. documentId 为 0/负数返回 400 参数校验错误（不进 Service）。</p>
 *
 * <p><strong>为什么需要 @MockitoBean 注入 TokenService？</strong>
 * 与 AiChatControllerTest 相同：认证过滤器依赖 TokenService 解析 Bearer Token，
 * mock 后对 valid-token 返回固定用户 ID 即视为已登录。</p>
 *
 * <p><strong>为什么不需要 ES 连接？</strong>本测试 mock 掉整个
 * {@link DocumentVectorizeService}，Controller 不会触发任何真实向量化逻辑；
 * 且未配置 SILICONFLOW_API_KEY 时 EmbeddingModel/VectorStore Bean 本就不装配，
 * 应用上下文可正常启动。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentVectorizeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentVectorizeService documentVectorizeService;

    @MockitoBean
    private TokenService tokenService;

    /** 已认证的请求头（TokenService.resolveUserId 对该 token 返回用户 ID 即视为已登录）。 */
    private static final String AUTH_HEADER = "Bearer valid-token";

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        // 不带认证头 → 401，验证向量化接口默认受 Security 保护
        mockMvc.perform(post("/api/ai/documents/vectorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "documentId": 1 }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(documentVectorizeService);
    }

    @Test
    void shouldReturnChunkCountForValidDocumentId() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(documentVectorizeService.vectorize(1L))
                .thenReturn(DocumentVectorizeVO.of(1L, 3, 1024));

        mockMvc.perform(post("/api/ai/documents/vectorize")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "documentId": 1 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.documentId").value(1))
                .andExpect(jsonPath("$.data.chunkCount").value(3))
                .andExpect(jsonPath("$.data.vectorSize").value(1024));
    }

    @Test
    void shouldRejectZeroDocumentId() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // documentId 为 0 → 参数校验失败 400，且不调用 Service
        mockMvc.perform(post("/api/ai/documents/vectorize")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "documentId": 0 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(documentVectorizeService);
    }

    @Test
    void shouldRejectNegativeDocumentId() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // documentId 为负数 → 参数校验失败 400（@Positive 拦截）
        mockMvc.perform(post("/api/ai/documents/vectorize")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "documentId": -5 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(documentVectorizeService);
    }

    @Test
    void shouldReturnNotFoundWhenDocumentMissing() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(documentVectorizeService.vectorize(anyLong()))
                .thenThrow(new io.github.qwertyhgb.knowflow.common.exception.BusinessException(
                        io.github.qwertyhgb.knowflow.common.exception.ErrorCode.DOCUMENT_NOT_FOUND));

        mockMvc.perform(post("/api/ai/documents/vectorize")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "documentId": 99 }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void shouldReturnBadRequestWhenDocumentNotReady() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(documentVectorizeService.vectorize(anyLong()))
                .thenThrow(new io.github.qwertyhgb.knowflow.common.exception.BusinessException(
                        io.github.qwertyhgb.knowflow.common.exception.ErrorCode.DOCUMENT_NOT_READY));

        mockMvc.perform(post("/api/ai/documents/vectorize")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "documentId": 1 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_READY"));
    }
}
