package io.github.qwertyhgb.knowflow.ai.service.impl;

import io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService;
import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link SemanticSearchServiceImpl} 单元测试：纯 Mockito，不依赖真实 ES / 硅基流动网络调用。
 *
 * <p>测试核心：
 * 1. 正常检索——similaritySearch 返回的块被正确映射为 VO（来源信息 + 文本 + 分数）；
 * 2. 未装配——EmbeddingModel/VectorStore 缺省时返回 503；
 * 3. 底层异常——embedding 服务/ES 调用失败转 503；
 * 4. score 缺失——结果不带分数时 VO.score 为 null（不抛 NPE，分数是可空字段）。</p>
 *
 * <p><strong>为什么不用 mock embeddingModel.embed？</strong>
 * Spring AI 2.0 的 {@code similaritySearch(SearchRequest)} 内部会调用 embeddingModel
 * 把问题向量化（VectorStore 抽象屏蔽了细节）。单元测试中 VectorStore 是 mock，
 * 不会真正触发 embed；embeddingModel 只用于判空检查。</p>
 */
@ExtendWith(MockitoExtension.class)
class SemanticSearchServiceImplTest {

    @Mock
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Mock
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorStore vectorStore;

    private SemanticSearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new SemanticSearchServiceImpl(embeddingModelProvider, vectorStoreProvider);
    }

    @Test
    void shouldMapHitsToVos() {
        // 场景：问题已装配模型与向量库，similaritySearch 返回 2 个命中块
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                // 用 Document.Builder 构造带 metadata 与 score 的命中块
                // （模拟第一步落库时写入的来源信息 + ES 返回的相似度分数）
                Document.builder()
                        .id("1-0")
                        .text("Redis 缓存可以显著降低数据库查询压力")
                        .metadata(Map.of(
                                "documentId", 1L,
                                "knowledgeBaseId", 2L,
                                "fileName", "缓存设计.md",
                                "chunkIndex", 0))
                        .score(0.87)
                        .build(),
                Document.builder()
                        .id("1-1")
                        .text("使用多级缓存进一步减少热点数据穿透")
                        .metadata(Map.of(
                                "documentId", 1L,
                                "knowledgeBaseId", 2L,
                                "fileName", "缓存设计.md",
                                "chunkIndex", 1))
                        .score(0.75)
                        .build()));

        List<SemanticSearchVO> results = searchService.search("如何提升系统查询速度", 2);

        // 数量与顺序保持（similaritySearch 已按相似度降序返回）
        assertEquals(2, results.size());

        // 第一条块：来源信息 + 文本 + 分数全部映射正确
        SemanticSearchVO first = results.get(0);
        assertEquals(1L, first.getDocumentId());
        assertEquals(2L, first.getKnowledgeBaseId());
        assertEquals("缓存设计.md", first.getFileName());
        assertEquals(0, first.getChunkIndex());
        assertEquals("Redis 缓存可以显著降低数据库查询压力", first.getChunkText());
        assertEquals(0.87, first.getScore());

        // 第二条块：序号与分数正确
        SemanticSearchVO second = results.get(1);
        assertEquals(1, second.getChunkIndex());
        assertEquals(0.75, second.getScore());
    }

    @Test
    void shouldThrowServiceUnavailableWhenNotConfigured() {
        // 场景：未配置 SILICONFLOW_API_KEY → EmbeddingModel/VectorStore 均未装配 → 503
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> searchService.search("问题", 5));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void shouldThrowServiceUnavailableWhenUnderlyingFails() {
        // 场景：底层检索抛异常（如 ES 连接失败、embedding 服务超时）→ 转 503 结构化错误
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("upstream failure"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> searchService.search("问题", 5));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void shouldTolerateMissingScore() {
        // 场景：向量库实现不返回分数（Document.score 为 null）→ VO.score 为 null，不抛 NPE
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        // Builder 不调用 score()，构建出的 Document.score 即为 null
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .id("1-0")
                        .text("块内容")
                        .metadata(Map.of(
                                "documentId", 1L,
                                "knowledgeBaseId", 2L,
                                "fileName", "文档.md",
                                "chunkIndex", 0))
                        .build()));

        List<SemanticSearchVO> results = searchService.search("问题", 5);

        assertEquals(1, results.size());
        assertNull(results.get(0).getScore(), "分数缺失时应为 null 而非抛异常");
        // 其余字段不受影响
        assertEquals("块内容", results.get(0).getChunkText());
        assertEquals(1L, results.get(0).getDocumentId());
    }

    @Test
    void shouldFailFastWhenDocumentIdMissing() {
        // 场景：块缺少 documentId（异常数据——正常向量化写入的 metadata 一定带 documentId）。
        // 【为什么这里要快速失败（抛 NPE）而不是兜底返回 null？】
        // documentId 是检索结果反查来源的核心：缺失意味着向量库里有「来路不明」的块
        // （例如绕过了第一步向量化直接写入的脏数据）。返回一个无法定位来源、前端无法
        // 跳转的残缺结果，比直接抛错暴露数据问题更糟——快速失败能让问题尽早暴露。
        // 因此 SemanticSearchVO 用 Objects.requireNonNull 强制 documentId 非空。
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .id("9-0")
                        .text("只有文本没有元数据")
                        .build()));

        assertThrows(NullPointerException.class, () -> searchService.search("问题", 5));
    }
}
