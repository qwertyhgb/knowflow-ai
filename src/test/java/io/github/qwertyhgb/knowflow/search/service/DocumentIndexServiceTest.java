package io.github.qwertyhgb.knowflow.search.service;

import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.enums.DocumentStatus;
import io.github.qwertyhgb.knowflow.search.entity.DocumentIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * {@link DocumentIndexService} 单元测试：纯 Mockito，不依赖真实 ES。
 *
 * <p>只验证「MySQL Document → ES DocumentIndex」的显式字段组装与 save 委托；
 * 真实 ES 的写入/查询行为由集成环境验证（本模块遵循「懒连接 + test profile 不连 ES」约定）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentIndexServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-17T03:00:00Z");

    @Mock
    private ElasticsearchOperations operations;

    private DocumentIndexService documentIndexService;

    @BeforeEach
    void setUp() {
        documentIndexService = new DocumentIndexService(operations);
    }

    @Test
    void shouldAssembleAllFieldsAndSaveOnce() {
        Document document = new Document();
        document.setId(200L);
        document.setEnterpriseId(10L);
        document.setKnowledgeBaseId(100L);
        document.setFileName("report.pdf");
        document.setContentType("application/pdf");
        document.setContent("KnowFlow 解析后的纯文本内容");
        document.setStatus(DocumentStatus.READY);
        document.setCreatedAt(CREATED_AT);

        documentIndexService.indexDocument(document);

        // save 只被调用一次，且携带的 DocumentIndex 字段逐项与 MySQL 实体对应。
        ArgumentCaptor<DocumentIndex> captor = ArgumentCaptor.forClass(DocumentIndex.class);
        verify(operations).save(captor.capture());
        DocumentIndex saved = captor.getValue();
        assertEquals(200L, saved.getDocumentId());
        assertEquals(10L, saved.getEnterpriseId());
        assertEquals(100L, saved.getKnowledgeBaseId());
        assertEquals("report.pdf", saved.getFileName());
        assertEquals("application/pdf", saved.getContentType());
        assertEquals("KnowFlow 解析后的纯文本内容", saved.getContent());
        assertEquals(CREATED_AT, saved.getCreatedAt());
    }

    @Test
    void shouldWriteNullsWhenDocumentFieldsMissing() {
        // 防御性场景：MySQL 实体字段为 null（如 contentType 允许为空）时，
        // 组装结果保持 null，不做隐式转换/拼接，避免脏数据进入 ES。
        Document document = new Document();
        document.setId(300L);
        document.setEnterpriseId(10L);
        document.setKnowledgeBaseId(100L);

        documentIndexService.indexDocument(document);

        ArgumentCaptor<DocumentIndex> captor = ArgumentCaptor.forClass(DocumentIndex.class);
        verify(operations).save(captor.capture());
        DocumentIndex saved = captor.getValue();
        assertEquals(300L, saved.getDocumentId());
        assertEquals(10L, saved.getEnterpriseId());
        assertEquals(100L, saved.getKnowledgeBaseId());
        assertEquals(null, saved.getFileName());
        assertEquals(null, saved.getContentType());
        assertEquals(null, saved.getContent());
        assertEquals(null, saved.getCreatedAt());
    }
}