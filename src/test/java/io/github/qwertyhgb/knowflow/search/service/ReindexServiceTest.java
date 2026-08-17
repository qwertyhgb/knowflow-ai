package io.github.qwertyhgb.knowflow.search.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.enums.DocumentStatus;
import io.github.qwertyhgb.knowflow.knowledge.mapper.DocumentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReindexService} 单元测试：纯 Mockito，不依赖真实 ES 或 MySQL。
 *
 * <p>测试核心：分页遍历的正确性、单条失败不中断、空页停止。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReindexServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentIndexService documentIndexService;

    private ReindexService reindexService;

    @BeforeAll
    static void initTableInfo() {
        // MyBatis-Plus Lambda 查询需要初始化实体类的表信息缓存，
        // 与 KnowledgeBaseServiceImplTest 的 @BeforeAll 模式一致。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Document.class);
    }

    @BeforeEach
    void setUp() {
        reindexService = new ReindexService(documentMapper, documentIndexService);
    }

    @Test
    void shouldReindexAllReadyDocumentsSuccessfully() {
        // 构造 3 篇 READY 文档（少于 PAGE_SIZE=100，一次分页读完）。
        Document doc1 = readyDocument(1L, 10L);
        Document doc2 = readyDocument(2L, 10L);
        Document doc3 = readyDocument(3L, 20L);
        Page<Document> page = new Page<>(1, 100);
        page.setRecords(List.of(doc1, doc2, doc3));
        page.setTotal(3);
        when(documentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(page);

        reindexService.reindexAllReadyDocuments();

        // 3 篇文档每篇都调用了 indexDocument。
        verify(documentIndexService).indexDocument(doc1);
        verify(documentIndexService).indexDocument(doc2);
        verify(documentIndexService).indexDocument(doc3);
        // 验证查询条件包含 READY 状态。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Document>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(documentMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        // MyBatis-Plus 的 SQL segment 格式包含参数占位符，只需验证包含 status 条件
        // 和 ORDER BY id ASC。
        assertTrue(sql.contains("status"), "查询条件应包含 status 过滤");
        assertTrue(sql.contains("ORDER BY id ASC"), "查询应包含 ORDER BY id ASC");
    }

    @Test
    void shouldContinueWhenSingleDocumentFails() {
        // 3 篇文档，中间那篇 indexDocument 失败。
        Document doc1 = readyDocument(1L, 10L);
        Document doc2 = readyDocument(2L, 10L);
        Document doc3 = readyDocument(3L, 20L);
        Page<Document> page = new Page<>(1, 100);
        page.setRecords(List.of(doc1, doc2, doc3));
        page.setTotal(3);
        when(documentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(page);
        // 使用 doAnswer 让 doc2 的 indexDocument 调用失败，其余正常。
        doAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            if (doc.getId().equals(2L)) {
                throw new RuntimeException("ES write failed");
            }
            return null;
        }).when(documentIndexService).indexDocument(any(Document.class));

        reindexService.reindexAllReadyDocuments();

        // doc1 成功，doc2 失败但继续，doc3 成功。
        verify(documentIndexService).indexDocument(doc1);
        verify(documentIndexService).indexDocument(doc2);
        verify(documentIndexService).indexDocument(doc3);
    }

    @Test
    void shouldStopWhenEmptyPage() {
        // 第一次分页返回 100 条（等于 PAGE_SIZE），第二次返回空 → 遍历停止。
        // 构造 100 条记录触发继续翻页。
        Document doc = readyDocument(1L, 10L);
        // 用 100 个相同引用的文档填满一页（不关心具体内容，只关心分页逻辑）。
        java.util.List<Document> hundredDocs = java.util.Collections.nCopies(100, doc);
        Page<Document> page1 = new Page<>(1, 100);
        page1.setRecords(new java.util.ArrayList<>(hundredDocs));
        page1.setTotal(100);

        Page<Document> page2 = new Page<>(2, 100);
        page2.setRecords(List.of());
        page2.setTotal(0);

        when(documentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(page1, page2);

        reindexService.reindexAllReadyDocuments();

        // page1 有 100 条记录，page2 为空 → indexDocument 被调用了 100 次。
        verify(documentIndexService, times(100)).indexDocument(any(Document.class));
        // verify 被调用了两次（page1 和 page2），分别传入不同的 Page 对象。
        // 由于 Page 没有实现 equals，用 ArgumentCaptor 捕获页面参数验证页码。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<Document>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        // 验证 selectPage 被调用了 2 次。
        verify(documentMapper, times(2)).selectPage(pageCaptor.capture(), any());
        assertEquals(1L, pageCaptor.getAllValues().get(0).getCurrent());
        assertEquals(2L, pageCaptor.getAllValues().get(1).getCurrent());
    }

    // ==================== 辅助方法 ====================

    private Document readyDocument(Long id, Long enterpriseId) {
        Document doc = new Document();
        doc.setId(id);
        doc.setEnterpriseId(enterpriseId);
        doc.setKnowledgeBaseId(100L);
        doc.setUploaderUserId(7L);
        doc.setFileName("test.pdf");
        doc.setFileSize(1024L);
        doc.setContentType("application/pdf");
        doc.setFileHash("a".repeat(64));
        doc.setStorageKey("uuid.pdf");
        doc.setStatus(DocumentStatus.READY);
        doc.setContent("测试文档内容");
        doc.setCreatedAt(NOW);
        doc.setUpdatedAt(NOW);
        return doc;
    }
}