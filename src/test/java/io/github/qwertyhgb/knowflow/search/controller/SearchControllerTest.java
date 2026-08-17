package io.github.qwertyhgb.knowflow.search.controller;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRolePermissionMapper;
import io.github.qwertyhgb.knowflow.search.entity.DocumentIndex;
import io.github.qwertyhgb.knowflow.search.service.DocumentSearchService;
import io.github.qwertyhgb.knowflow.search.vo.DocumentSearchVO;
import io.github.qwertyhgb.knowflow.search.vo.SearchResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜索接口的 Web 层测试：认证、企业上下文、参数校验、JSON 结构与统一响应格式。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private DocumentSearchService documentSearchService;

    @MockitoBean
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @MockitoBean
    private EnterpriseRoleMapper enterpriseRoleMapper;

    @MockitoBean
    private EnterpriseRolePermissionMapper enterpriseRolePermissionMapper;

    @BeforeEach
    void stubEnterpriseContext() {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(10L);
        member.setUserId(7L);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        member.setRoleId(100L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);

        EnterpriseRole role = new EnterpriseRole();
        role.setId(100L);
        role.setCode("OWNER");
        role.setStatus(EnterpriseRoleStatus.NORMAL);
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role);
        when(enterpriseRolePermissionMapper.selectPermissionCodesByRoleId(any()))
                .thenReturn(List.of("OWNER", "ADMIN", "MEMBER_ADD", "MEMBER_REMOVE", "MEMBER_UPDATE",
                        "KNOWLEDGE_BASE_CREATE", "KNOWLEDGE_BASE_UPDATE", "KNOWLEDGE_BASE_DELETE",
                        "DEPARTMENT_CREATE", "DEPARTMENT_UPDATE", "DEPARTMENT_DELETE",
                        "INVITATION_CREATE", "INVITATION_REVOKE",
                        "MEMBER_REMOVE", "MEMBER_UPDATE", "ENTERPRISE_SEARCH"));
    }

    // ==================== 成功场景 ====================

    @Test
    void shouldSearchSuccessfully() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        DocumentIndex docIndex = new DocumentIndex();
        docIndex.setDocumentId(100L);
        docIndex.setKnowledgeBaseId(1L);
        docIndex.setFileName("report.pdf");
        docIndex.setContentType("application/pdf");
        docIndex.setContent("KnowFlow 搜索内容");
        docIndex.setCreatedAt(Instant.parse("2026-08-17T03:00:00Z"));
        SearchHit<DocumentIndex> hit = new SearchHit<>(
                "knowflow-document", "100", "100", 1.5f, new Object[0],
                Map.of("content", List.of("KnowFlow <em>搜索</em>内容")),
                Map.of(), null, null, Map.of(), docIndex);
        DocumentSearchVO item = DocumentSearchVO.from(hit);
        SearchResultVO result = new SearchResultVO(List.of(item), 1L, 1, 10);
        when(documentSearchService.searchDocuments(eq(7L), eq(10L), eq("搜索"), eq(1), eq(10)))
                .thenReturn(result);

        mockMvc.perform(get("/api/enterprises/10/search")
                        .param("keyword", "搜索")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].documentId").value(100))
                .andExpect(jsonPath("$.data.items[0].fileName").value("report.pdf"))
                .andExpect(jsonPath("$.data.items[0].contentSnippet").value("KnowFlow <em>搜索</em>内容"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10));

        verify(documentSearchService).searchDocuments(eq(7L), eq(10L), eq("搜索"), eq(1), eq(10));
    }

    @Test
    void shouldSearchWithCustomPagination() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        SearchResultVO result = new SearchResultVO(List.of(), 0L, 2, 20);
        when(documentSearchService.searchDocuments(anyLong(), anyLong(), anyString(), anyInt(), anyInt()))
                .thenReturn(result);

        mockMvc.perform(get("/api/enterprises/10/search")
                        .param("keyword", "test")
                        .param("page", "2")
                        .param("size", "20")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    // ==================== 参数校验 ====================

    @Test
    void shouldRejectMissingKeyword() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(get("/api/enterprises/10/search")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(documentSearchService);
    }

    @Test
    void shouldRejectInvalidPage() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(get("/api/enterprises/10/search")
                        .param("keyword", "test")
                        .param("page", "0")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(documentSearchService);
    }

    @Test
    void shouldRejectInvalidSize() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(get("/api/enterprises/10/search")
                        .param("keyword", "test")
                        .param("size", "200")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(documentSearchService);
    }

    @Test
    void shouldRejectSizeZero() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(get("/api/enterprises/10/search")
                        .param("keyword", "test")
                        .param("size", "0")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(documentSearchService);
    }

    // ==================== 认证与上下文 ====================

    @Test
    void shouldReturnUnauthorizedWhenNoToken() throws Exception {
        mockMvc.perform(get("/api/enterprises/10/search")
                        .param("keyword", "test")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(documentSearchService);
    }

    @Test
    void shouldRejectMissingEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(get("/api/enterprises/10/search")
                        .param("keyword", "test")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(documentSearchService);
    }

    // ==================== 业务异常传递 ====================

    @Test
    void shouldReturnForbiddenWhenNotMember() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(documentSearchService).searchDocuments(eq(7L), eq(10L), eq("test"), eq(1), eq(10));

        mockMvc.perform(get("/api/enterprises/10/search")
                        .param("keyword", "test")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnKeywordRequiredWhenServiceThrows() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.SEARCH_KEYWORD_REQUIRED))
                .when(documentSearchService).searchDocuments(eq(7L), eq(10L), eq(""), eq(1), eq(10));

        mockMvc.perform(get("/api/enterprises/10/search")
                        .param("keyword", "")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SEARCH_KEYWORD_REQUIRED"));
    }
}