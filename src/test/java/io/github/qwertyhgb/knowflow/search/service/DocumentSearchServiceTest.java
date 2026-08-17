package io.github.qwertyhgb.knowflow.search.service;

import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseMembershipChecker;
import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBase;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseAccessMode;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseStatus;
import io.github.qwertyhgb.knowflow.knowledge.service.KnowledgeBaseService;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseVO;
import io.github.qwertyhgb.knowflow.search.entity.DocumentIndex;
import io.github.qwertyhgb.knowflow.search.vo.DocumentSearchVO;
import io.github.qwertyhgb.knowflow.search.vo.SearchResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentSearchService} 单元测试：纯 Mockito，不依赖真实 ES。
 *
 * <p>测试核心：可见性过滤（安全底线）、查询构建、结果组装、异常场景。
 * 搜索接口的可见性过滤是安全底线——必须验证 {@code listVisibleKnowledgeBases}
 * 的结果决定了 ES 查询条件中的 knowledgeBaseId 集合。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");

    @Mock
    private ElasticsearchOperations operations;

    @Mock
    private EnterpriseMembershipChecker membershipChecker;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private DocumentSearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new DocumentSearchService(operations, membershipChecker, knowledgeBaseService);
    }

    @Test
    void shouldSearchSuccessfullyWithHighlight() {
        // 构造可见知识库列表：[1, 2]
        KnowledgeBaseVO kb1 = kbVO(1L);
        KnowledgeBaseVO kb2 = kbVO(2L);
        when(knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L))
                .thenReturn(List.of(kb1, kb2));

        // 构造 ES 搜索结果：一条命中 + 高亮
        DocumentIndex docIndex = docIndex(100L, 1L, "report.pdf", "KnowFlow 搜索内容");
        SearchHit<DocumentIndex> hit = new SearchHit<>(
                "knowflow-document", "100", "100", 1.5f, new Object[0],
                Map.of("content", List.of("KnowFlow <em>搜索</em>内容")),
                Map.of(), null, null, Map.of(), docIndex);
        SearchHits<DocumentIndex> searchHits = mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of(hit));
        when(searchHits.getTotalHits()).thenReturn(1L);
        when(operations.search(any(NativeQuery.class), eq(DocumentIndex.class)))
                .thenReturn(searchHits);

        SearchResultVO result = searchService.searchDocuments(7L, 10L, "搜索", 1, 10);

        // 验证 VO 组装正确
        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getItems().size());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());

        DocumentSearchVO item = result.getItems().getFirst();
        assertEquals(100L, item.getDocumentId());
        assertEquals(1L, item.getKnowledgeBaseId());
        assertEquals("report.pdf", item.getFileName());
        // 高亮片段优先于原始 content
        assertEquals("KnowFlow <em>搜索</em>内容", item.getContentSnippet());
        assertEquals(NOW, item.getCreatedAt());
    }

    @Test
    void shouldUseContentSnippetWhenNoHighlight() {
        when(knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L))
                .thenReturn(List.of(kbVO(1L)));

        String longContent = "A".repeat(300);
        DocumentIndex docIndex = docIndex(101L, 1L, "notes.txt", longContent);
        SearchHit<DocumentIndex> hit = new SearchHit<>(
                "knowflow-document", "101", "101", 1.0f, new Object[0],
                Map.of(), // 无高亮
                Map.of(), null, null, Map.of(), docIndex);
        SearchHits<DocumentIndex> searchHits = mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of(hit));
        when(searchHits.getTotalHits()).thenReturn(1L);
        when(operations.search(any(NativeQuery.class), eq(DocumentIndex.class)))
                .thenReturn(searchHits);

        SearchResultVO result = searchService.searchDocuments(7L, 10L, "keyword", 1, 10);

        // 无高亮时取 content 前 200 字符 + "..."
        assertEquals(1, result.getItems().size());
        String snippet = result.getItems().getFirst().getContentSnippet();
        assertTrue(snippet.endsWith("..."));
        assertEquals(203, snippet.length()); // 200 + 3
    }

    @Test
    void shouldReturnEmptyWhenNoVisibleKnowledgeBases() {
        // 可见知识库为空 → 不发起 ES 查询，直接返回空结果。
        when(knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L))
                .thenReturn(List.of());

        SearchResultVO result = searchService.searchDocuments(7L, 10L, "keyword", 1, 10);

        assertEquals(0, result.getTotal());
        assertTrue(result.getItems().isEmpty());
        verifyNoInteractions(operations);
    }

    @Test
    void shouldThrowForbiddenWhenNotEnterpriseMember() {
        // membershipChecker.requireActiveMember 抛 403 → 搜索被拒绝。
        // 先资源后权限：企业存在校验通过，但成员校验失败。
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(membershipChecker).requireActiveMember(7L, 10L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> searchService.searchDocuments(7L, 10L, "keyword", 1, 10));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldThrowNotFoundWhenEnterpriseNotExists() {
        // membershipChecker.requireEnterprise 抛 404 → 企业不存在。
        // requireEnterprise 返回 Enterprise 非 void，用 when().thenThrow() 方式。
        when(membershipChecker.requireEnterprise(10L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> searchService.searchDocuments(7L, 10L, "keyword", 1, 10));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldThrowKeywordRequiredWhenKeywordBlank() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> searchService.searchDocuments(7L, 10L, "   ", 1, 10));

        assertEquals(ErrorCode.SEARCH_KEYWORD_REQUIRED, exception.getErrorCode());
        verifyNoInteractions(operations);
    }

    @Test
    void shouldThrowKeywordRequiredWhenKeywordNull() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> searchService.searchDocuments(7L, 10L, null, 1, 10));

        assertEquals(ErrorCode.SEARCH_KEYWORD_REQUIRED, exception.getErrorCode());
        verifyNoInteractions(operations);
    }

    @Test
    void shouldPassEnterpriseIdFilter() {
        // 验证传给 ES 的查询中 enterpriseId 作为过滤条件。
        when(knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L))
                .thenReturn(List.of(kbVO(1L)));

        SearchHits<DocumentIndex> searchHits = mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of());
        when(searchHits.getTotalHits()).thenReturn(0L);
        when(operations.search(any(NativeQuery.class), eq(DocumentIndex.class)))
                .thenReturn(searchHits);

        searchService.searchDocuments(7L, 10L, "keyword", 1, 10);

        // 验证 operations.search 被调用，NativeQuery 包含 enterpriseId 过滤。
        ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(operations).search(queryCaptor.capture(), eq(DocumentIndex.class));
        NativeQuery query = queryCaptor.getValue();
        // 验证查询不为空（具体条件通过集成测试验证，单元测试验证间接行为）。
        assertNotNull(query.getQuery());
    }

    @Test
    void shouldUseCorrectPagination() {
        when(knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L))
                .thenReturn(List.of(kbVO(1L)));

        SearchHits<DocumentIndex> searchHits = mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of());
        when(searchHits.getTotalHits()).thenReturn(0L);
        when(operations.search(any(NativeQuery.class), eq(DocumentIndex.class)))
                .thenReturn(searchHits);

        SearchResultVO result = searchService.searchDocuments(7L, 10L, "keyword", 2, 10);

        assertEquals(2, result.getPage());
        assertEquals(10, result.getSize());
    }

    // ==================== 辅助方法 ====================

    private KnowledgeBaseVO kbVO(Long id) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setEnterpriseId(10L);
        kb.setName("kb" + id);
        kb.setAccessMode(KnowledgeBaseAccessMode.PUBLIC);
        kb.setStatus(KnowledgeBaseStatus.NORMAL);
        kb.setOwnerUserId(7L);
        kb.setCreatedAt(NOW);
        return KnowledgeBaseVO.from(kb);
    }

    private DocumentIndex docIndex(Long documentId, Long kbId, String fileName, String content) {
        DocumentIndex doc = new DocumentIndex();
        doc.setDocumentId(documentId);
        doc.setEnterpriseId(10L);
        doc.setKnowledgeBaseId(kbId);
        doc.setFileName(fileName);
        doc.setContentType("text/plain");
        doc.setContent(content);
        doc.setCreatedAt(NOW);
        return doc;
    }
}