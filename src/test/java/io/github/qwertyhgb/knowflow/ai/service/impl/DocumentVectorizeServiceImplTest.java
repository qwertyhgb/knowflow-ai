package io.github.qwertyhgb.knowflow.ai.service.impl;

import io.github.qwertyhgb.knowflow.ai.service.DocumentVectorizeService;
import io.github.qwertyhgb.knowflow.ai.vo.DocumentVectorizeVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.enums.DocumentStatus;
import io.github.qwertyhgb.knowflow.knowledge.mapper.DocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentVectorizeServiceImpl} 单元测试：纯 Mockito，不依赖真实 ES / 硅基流动网络调用。
 *
 * <p>测试核心：
 * 1. 正常路径——READY 文档被切块、向量化并写入 VectorStore，metadata 含 documentId；
 * 2. 异常路径——文档不存在（404）、状态非 READY（400）、content 为空（400）；
 * 3. 幂等——重复向量化先按 documentId 删除旧向量再写入。</p>
 *
 * <p><strong>为什么只 mock VectorStore.add 而不 mock embeddingModel.embed？</strong>
 * Spring AI 2.0 的 {@code VectorStore.add(List<Document>)} 内部会自动调用 embeddingModel
 * 计算向量（Document 没有 setEmbedding 方法）。单元测试中 VectorStore 是 mock，
 * 不会真正触发 embed；embeddingModel 只用于判空与 dimensions()。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentVectorizeServiceImplTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Mock
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorStore vectorStore;

    private DocumentVectorizeService vectorizeService;

    @BeforeEach
    void setUp() {
        vectorizeService = new DocumentVectorizeServiceImpl(
                documentMapper, embeddingModelProvider, vectorStoreProvider);
    }

    @Test
    void shouldVectorizeReadyDocument() {
        // 场景：文档已解析完成（READY）且有内容
        Document document = readyDocument("A".repeat(2000));
        when(documentMapper.selectById(1L)).thenReturn(document);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(embeddingModel.dimensions()).thenReturn(1024);

        DocumentVectorizeVO vo = vectorizeService.vectorize(1L);

        // 2000 字符 / 800 字符每块 + 100 字符重叠 → 3 块（0-800, 700-1500, 1400-2000）
        assertEquals(1L, vo.getDocumentId());
        assertEquals(3, vo.getChunkCount());
        assertEquals(1024, vo.getVectorSize());

        // 捕获传入 vectorStore.add 的文档列表，验证块数与 metadata
        ArgumentCaptor<List<org.springframework.ai.document.Document>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<org.springframework.ai.document.Document> added = captor.getValue();
        assertEquals(3, added.size());
        // 块 ID 为「文档ID-块序号」，metadata 含 documentId / knowledgeBaseId / chunkIndex
        assertEquals("1-0", added.get(0).getId());
        assertEquals(1L, added.get(0).getMetadata().get("documentId"));
        assertEquals(2L, added.get(0).getMetadata().get("knowledgeBaseId"));
        assertEquals(0, added.get(0).getMetadata().get("chunkIndex"));
        assertEquals("1-2", added.get(2).getId());
        assertEquals(2, added.get(2).getMetadata().get("chunkIndex"));
    }

    @Test
    void shouldThrowNotFoundWhenDocumentMissing() {
        // 场景：文档不存在 → 404 DOCUMENT_NOT_FOUND
        when(documentMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> vectorizeService.vectorize(99L));

        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, exception.getErrorCode());
        // 不应对 VectorStore 做任何操作
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void shouldThrowNotReadyWhenDocumentNotReady() {
        // 场景：文档处于 UPLOADED（尚未解析）→ 400 DOCUMENT_NOT_READY
        Document document = new Document();
        document.setId(1L);
        document.setStatus(DocumentStatus.UPLOADED);
        when(documentMapper.selectById(1L)).thenReturn(document);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> vectorizeService.vectorize(1L));

        assertEquals(ErrorCode.DOCUMENT_NOT_READY, exception.getErrorCode());
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void shouldThrowNotReadyWhenDocumentFailed() {
        // 场景：文档解析失败（FAILED）→ 400 DOCUMENT_NOT_READY（失败内容不可靠，不向量化）
        Document document = new Document();
        document.setId(1L);
        document.setStatus(DocumentStatus.FAILED);
        when(documentMapper.selectById(1L)).thenReturn(document);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> vectorizeService.vectorize(1L));

        assertEquals(ErrorCode.DOCUMENT_NOT_READY, exception.getErrorCode());
    }

    @Test
    void shouldThrowNotReadyWhenContentBlank() {
        // 场景：READY 但 content 为 null（理论异常态）→ 400，防御性校验兜底
        Document document = new Document();
        document.setId(1L);
        document.setStatus(DocumentStatus.READY);
        document.setContent(null);
        when(documentMapper.selectById(1L)).thenReturn(document);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> vectorizeService.vectorize(1L));

        assertEquals(ErrorCode.DOCUMENT_NOT_READY, exception.getErrorCode());
    }

    @Test
    void shouldDeleteOldVectorsBeforeAddOnRepeat() {
        // 场景：重复向量化同一文档——先按 documentId 过滤删除旧向量，再写入新向量，
        // 保证不产生堆积的重复块（幂等策略）。
        Document document = readyDocument("A".repeat(500));
        when(documentMapper.selectById(1L)).thenReturn(document);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(embeddingModel.dimensions()).thenReturn(1024);

        vectorizeService.vectorize(1L);

        // 删除必须发生（verify delete 被调用），且 add 只写入 1 块（500 字符 < 800）
        verify(vectorStore).delete(org.mockito.ArgumentMatchers.any(
                org.springframework.ai.vectorstore.filter.Filter.Expression.class));
        ArgumentCaptor<List<org.springframework.ai.document.Document>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    @Test
    void shouldThrowServiceUnavailableWhenEmbeddingNotConfigured() {
        // 场景：未配置 SILICONFLOW_API_KEY → EmbeddingModel/VectorStore 均未装配 → 503
        Document document = readyDocument("内容");
        when(documentMapper.selectById(1L)).thenReturn(document);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> vectorizeService.vectorize(1L));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    /** 构造一个 READY 状态、含指定内容与归属信息的测试文档。 */
    private Document readyDocument(String content) {
        Document document = new Document();
        document.setId(1L);
        document.setEnterpriseId(10L);
        document.setKnowledgeBaseId(2L);
        document.setFileName("测试文档.md");
        document.setStatus(DocumentStatus.READY);
        document.setContent(content);
        return document;
    }
}
