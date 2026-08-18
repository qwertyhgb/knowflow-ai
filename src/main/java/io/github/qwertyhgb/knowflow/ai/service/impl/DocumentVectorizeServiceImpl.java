package io.github.qwertyhgb.knowflow.ai.service.impl;

import io.github.qwertyhgb.knowflow.ai.service.DocumentVectorizeService;
import io.github.qwertyhgb.knowflow.ai.vo.DocumentVectorizeVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.knowledge.entity.Document;
import io.github.qwertyhgb.knowflow.knowledge.enums.DocumentStatus;
import io.github.qwertyhgb.knowflow.knowledge.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档向量化服务实现。
 *
 * <p>核心链路：<strong>查文档 → 校验 → 删除旧向量（幂等）→ 切块 → 组装块 → 写入 ES</strong>。
 * 这是 Phase 10 的第一步：把文档内容转成语义向量落库，Phase 11 才能基于它做
 * RAG 语义检索。本步只做「数据入库」，检索在下一步。</p>
 *
 * <p><strong>为什么切块（Chunking）？</strong>三个理由：</p>
 * <ol>
 *   <li><strong>上下文窗口有限</strong>——大模型的上下文窗口有 token 上限，
 *       整篇长文档（几万字）无法一次性塞入；切块后每块都远小于窗口，可独立参与后续检索；</li>
 *   <li><strong>检索粒度更精准</strong>——语义检索的命中单元是「块」不是「整篇文档」：
 *       用户问的是文档里的某段内容，命中包含答案的那个块，比把整篇文档拖出来再让模型找
 *       要精准得多，成本也更低；</li>
 *   <li><strong>向量表达更聚焦</strong>——embedding 把文本映射为高维向量，语义相近
 *       文本向量距离近；短文（一块）语义单一、向量更聚焦，长文语义混杂、向量会被稀释，
 *       检索相关性下降。</li>
 * </ol>
 *
 * <p><strong>为什么用 ES 而不再引入向量数据库？</strong>教学文档原则：已有技术能满足
 * 就不引入新技术。项目已有 ES 9.4.2（Phase 8 全文搜索），它原生支持 dense_vector
 * + kNN 检索，完全能承担向量存储职责；Spring AI 的 ES 向量存储 starter 开箱即用，
 * 自动创建索引与 Mapping。另起炉灶引入 Milvus/Qdrant 属于重复造轮子。</p>
 *
 * <p><strong>为什么不需要手动调用 {@code embeddingModel.embed(...)}？</strong>
 * 查证 Spring AI 2.0 的
 * {@link org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore#doAdd}
 * 字节码发现：
 * {@code vectorStore.add(documents)} 内部会遍历文档，自动调用
 * {@code embeddingModel.embed(documents, ...)} 为每个 Document 计算向量再写入 ES
 * （`Document` 类没有 `setEmbedding` 方法，向量不是「放进 Document」而是「存储时计算」）。
 * 因此本实现只组装「文本 + 元数据」的 Document 并交给 {@code vectorStore.add(...)}，
 * 向量化由 VectorStore 内部完成——这也正是「VectorStore 统一抽象」的意义：
 * 调用方只关心「存什么」，不关心「怎么算向量、怎么存」。</p>
 */
@Slf4j
@Service
public class DocumentVectorizeServiceImpl implements DocumentVectorizeService {

    /**
     * 切块大小（字符数）。
     *
     * <p>经验值：800 字符约等于 300~500 token（中文每字约 1~2 token），
     * 是 embedding 与后续大模型上下文的均衡点——太小向量上下文不足、检索易碎片化，
     * 太大则失去切块意义。学习阶段固定值即可，高级切分（按段落/语义）是后续优化主题。</p>
     */
    private static final int CHUNK_SIZE = 800;

    /**
     * 相邻块的字符重叠量。
     *
     * <p><strong>为什么要重叠？</strong>防止语义被硬切在块边界断开：若某句话恰好在
     * 800 字符处被一分为二，前半句的「下半句」、后半句的「上半句」都残缺，向量化后
     * 语义都不完整，检索时可能都命中不了。让下一块从上一块结尾前 100 字符处开始，
     * 关键信息在相邻块中各保留一份，提高召回率。</p>
     */
    private static final int CHUNK_OVERLAP = 100;

    /** 查询 MySQL 文档记录。 */
    private final DocumentMapper documentMapper;

    /**
     * Embedding 模型的「可选」提供者：未配置 SILICONFLOW_API_KEY 时 EmbeddingModel
     * Bean 不存在，用 ObjectProvider.getIfAvailable() 判空后返回 503，而不是启动失败
     * （与 AiChatServiceImpl 对 ChatClient 的处理一致，AI 能力是可选的）。
     */
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /**
     * VectorStore（指向 ES 向量索引）的「可选」提供者：同上，它依赖 EmbeddingModel，
     * embedding 未装配时 VectorStore Bean 也不会存在，判空返回 503。
     */
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public DocumentVectorizeServiceImpl(DocumentMapper documentMapper,
                                        ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                        ObjectProvider<VectorStore> vectorStoreProvider) {
        this.documentMapper = documentMapper;
        this.embeddingModelProvider = embeddingModelProvider;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    @Override
    public DocumentVectorizeVO vectorize(Long documentId) {
        // ---- 1. 查文档 ----
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            log.warn("event=document_vectorize_failed reason=document_not_found documentId={}", documentId);
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        // ---- 2. 校验状态：只有 READY 且内容非空才能向量化 ----
        if (document.getStatus() != DocumentStatus.READY || isBlank(document.getContent())) {
            log.warn("event=document_vectorize_failed reason=document_not_ready documentId={} status={}",
                    documentId, document.getStatus());
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_READY);
        }

        // ---- 3. 检查 embedding / 向量存储是否可用 ----
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (embeddingModel == null || vectorStore == null) {
            log.warn("event=document_vectorize_failed reason=embedding_unavailable documentId={}", documentId);
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }

        try {
            // ---- 4. 幂等：先删除该文档的旧向量 ----
            // 重复向量化（如修改文档后重跑）不应堆积重复块：先按 documentId 过滤删除旧向量，
            // 再写新的。FilterExpressionBuilder 构造 metadata 过滤表达式
            // （documentId == {id}），ES 向量存储会把 metadata 字段索引为普通字段供过滤。
            FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
            vectorStore.delete(filterBuilder.eq("documentId", documentId).build());

            // ---- 5. 切块 ----
            List<String> chunks = splitIntoChunks(document.getContent());

            // ---- 6. 组装 Spring AI Document（文本 + 元数据）----
            List<org.springframework.ai.document.Document> aiDocuments = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                // 块 ID 用「文档ID-块序号」：保证同一文档的块可识别、可追溯，
                // 后续按文档 ID 过滤/删除某文档的全部块。
                String chunkId = documentId + "-" + i;
                // metadata 冗余文档归属信息：检索结果命中后能反查来源文档，
                // 权限过滤（Phase 11）也能按 knowledgeBaseId 做可见性裁剪。
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("documentId", documentId);
                metadata.put("knowledgeBaseId", document.getKnowledgeBaseId());
                metadata.put("fileName", document.getFileName());
                metadata.put("chunkIndex", i);

                aiDocuments.add(new org.springframework.ai.document.Document(chunkId, chunks.get(i), metadata));
            }

            // ---- 7. 写入 ES 向量索引 ----
            // 向量化在 VectorStore.add 内部完成：它调用 embeddingModel 为每个 Document
            // 计算向量（语义相近文本向量距离近，余弦相似度是向量检索的度量），
            // 再连同文本与 metadata 一起批量写入 ES 的 dense_vector 字段。
            vectorStore.add(aiDocuments);

            int vectorSize = embeddingModel.dimensions();
            log.info("event=document_vectorized documentId={} chunkCount={} vectorSize={}",
                    documentId, chunks.size(), vectorSize);
            return DocumentVectorizeVO.of(documentId, chunks.size(), vectorSize);
        } catch (RuntimeException e) {
            // 外部依赖（embedding 服务 / ES）调用失败：记录安全日志（不含文档内容原文），
            // 转成 503 结构化错误，与 AI 对话的失败处理语义一致。
            log.warn("event=document_vectorize_failed reason={} documentId={}",
                    e.getClass().getSimpleName(), documentId);
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /**
     * 简单切块：按固定字符数 {@link #CHUNK_SIZE} 切，相邻块重叠 {@link #CHUNK_OVERLAP} 字符。
     *
     * <p>教学注：这是打通链路的最简策略。更精细的按段落/句子/语义切分、以及块大小
     * 自适应，是后续优化主题——本步先保证「数据能进来、检索能用」，再谈切分质量。</p>
     */
    private List<String> splitIntoChunks(String content) {
        List<String> chunks = new ArrayList<>();
        int length = content.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);
            chunks.add(content.substring(start, end));
            if (end == length) {
                break;
            }
            // 下一块起点 = 本块结尾 - 重叠量，实现前后块内容交叠
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }

    /** 空白判断（null / 空串 / 纯空白）。 */
    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
