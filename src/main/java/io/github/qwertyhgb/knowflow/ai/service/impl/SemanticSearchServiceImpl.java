package io.github.qwertyhgb.knowflow.ai.service.impl;

import io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService;
import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语义搜索服务实现。
 *
 * <p>核心链路：<strong>Question → 问题向量化 + 相似度检索（一步完成）→ 映射为 VO</strong>。</p>
 *
 * <p><strong>语义搜索（Semantic Search）是什么？</strong>
 * 把问题文本映射为高维向量，与向量库中所有文档块向量计算余弦相似度，
 * 返回最相似的 Top K 块。关键在于它按「意思」匹配，而非关键词拼写：
 * 搜「怎么改密码」能命中「修改登录密码的步骤」，即使两个句子没有一个词相同——
 * 因为它们在语义空间中的向量距离很近。</p>
 *
 * <p><strong>与全文搜索（倒排索引）的对比：</strong>
 * 全文搜索（项目已有 GET /api/enterprises/{enterpriseId}/search）基于 ES 倒排索引
 * 做关键词精确匹配：快、准、可高亮，但跨词面失效（换个说法就搜不到）。
 * 向量搜索能跨词面找语义相近的内容，但需要先向量化、有计算成本、结果解释性弱。
 * <strong>两者互补</strong>：Phase 11 的 RAG 会组合使用——向量召回候选 + 全文/重排精排。</p>
 */
@Slf4j
@Service
public class SemanticSearchServiceImpl implements SemanticSearchService {

    /**
     * Embedding 模型的「可选」提供者：未配置 SILICONFLOW_API_KEY 时 EmbeddingModel
     * Bean 不存在，判空后返回 503（与 DocumentVectorizeServiceImpl 完全一致的可选模式）。
     */
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /**
     * VectorStore（ES 向量索引）的「可选」提供者：依赖 EmbeddingModel，未装配时判空 503。
     */
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public SemanticSearchServiceImpl(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                     ObjectProvider<VectorStore> vectorStoreProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    @Override
    public List<SemanticSearchVO> search(String question, int topK) {
        // ---- 1. 检查 embedding / 向量存储是否可用 ----
        // 语义搜索同时需要「能向量化问题」（EmbeddingModel）与「能查向量索引」（VectorStore），
        // 两者任一缺失都无法工作。判空返回 503 而非抛异常，让应用在未配 key 时正常启动。
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (embeddingModel == null || vectorStore == null) {
            log.warn("event=semantic_search_failed reason=embedding_unavailable");
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }

        // ---- 2. 构建检索请求并执行（外部调用，单独 try-catch）----
        // SearchRequest 封装了「问题文本 + 返回条数 + 可选过滤条件」。
        // 【为什么问题向量化也在这里？】查证 Spring AI 2.0 的 ES 向量存储：
        // similaritySearch(SearchRequest) 内部会先调用 embeddingModel 把 query 向量化，
        // 再做 kNN 相似度检索——与第一步「VectorStore.add 内部自动 embed」同理。
        // VectorStore 抽象对调用方屏蔽了「怎么算向量、怎么查」的全部细节，
        // 我们只表达检索意图（查什么、要几条），这正是统一抽象的意义。
        List<Document> hits;
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(question)
                    .topK(topK)
                    .build();
            hits = vectorStore.similaritySearch(searchRequest);
        } catch (RuntimeException e) {
            // 外部依赖（embedding 服务 / ES）调用失败：记录安全日志，转 503 结构化错误。
            // 【为什么映射（toVO）不在这个 try 里？】映射是本地数据解析，若 metadata
            // 损坏（如缺 documentId），应直接暴露数据问题（NPE → 500），而不是被误判成
            // 「AI 服务不可用」（503）——两类错误性质完全不同，不能混为一谈。
            log.warn("event=semantic_search_failed reason={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, e.getMessage());
        }

        // ---- 3. 把命中块映射为 VO（本地解析，不捕获）----
        // 检索命中的每个 Document 是从向量索引反查出来的完整块：text 是块原文，
        // metadata 是第一步落库时写入的来源信息（见 DocumentVectorizeServiceImpl），
        // score 是 ES 返回的相似度分数（余弦相似度归一化后 0~1，越高越相关）。
        List<SemanticSearchVO> results = new ArrayList<>(hits.size());
        for (Document hit : hits) {
            results.add(toVO(hit));
        }

        log.info("event=semantic_search queryVectorized=true topK={} hitCount={}",
                topK, results.size());
        return results;
    }

    /**
     * 把检索命中的 Spring AI Document 映射为 {@link SemanticSearchVO}。
     *
     * <p><strong>metadata 键名是跨步骤契约</strong>：这里读取的 documentId /
     * knowledgeBaseId / fileName / chunkIndex 必须与第一步写入时的键名完全一致
     * （见 {@link DocumentVectorizeServiceImpl}），拼错一个字符就会读到 null。
     * 由于 VectorStore 会完整保留写入时的 metadata（并额外追加 DISTANCE 等内部键），
     * 这里按「可能缺失」防御式取值，缺失时以 0/null 兜底而非抛异常。</p>
     */
    private SemanticSearchVO toVO(Document hit) {
        Map<String, Object> metadata = hit.getMetadata();
        // 从 metadata 读来源信息；类型转换要小心：Long 可能被序列化/反序列化为 Integer 或 Long，
        // 用 Number 中转兼容两种形态。
        Long documentId = asLong(metadata.get("documentId"));
        Long knowledgeBaseId = asLong(metadata.get("knowledgeBaseId"));
        String fileName = metadata.get("fileName") != null ? metadata.get("fileName").toString() : null;
        Object chunkIndexObj = metadata.get("chunkIndex");
        int chunkIndex = chunkIndexObj instanceof Number n ? n.intValue() : 0;

        // 分数取 Document.score：ES 实现会填充相似度分数；其他实现可能为 null，直接透传。
        Double score = hit.getScore();

        return SemanticSearchVO.of(documentId, knowledgeBaseId, fileName, chunkIndex,
                hit.getText(), score);
    }

    /** 把 metadata 中的 ID 值安全转为 Long（兼容 Integer/Long 两种反序列化形态）。 */
    private Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
