package io.github.qwertyhgb.knowflow.ai.vo;

import lombok.Getter;

import java.util.Objects;

/**
 * 语义搜索结果响应对象（一个命中块）。
 *
 * <p>检索命中的「块」需要能<strong>反查来源</strong>：documentId 定位来源文档、
 * knowledgeBaseId 归属知识库（Phase 11 权限过滤也靠它）、fileName 供前端展示、
 * chunkIndex 定位块在文档内的位置。这些信息来自向量化时写入块的 metadata——</p>
 *
 * <p><strong>为什么 metadata 键名是跨步骤契约？</strong>
 * 第一步（DocumentVectorizeServiceImpl）落库时把 documentId/knowledgeBaseId/fileName/
 * chunkIndex 写进每个块的 metadata；本步（SemanticSearchServiceImpl）从检索结果的
 * metadata 里按<strong>同名键</strong>读取。两个步骤之间没有任何编译器校验，
 * 键名拼错一个字符，本步就会读到 null——所以这四个键名是向量化与检索之间的
 * 「隐式契约」，必须保持一致。这也是「先定数据模型、再做读写两端」的教学意义。</p>
 */
@Getter
public class SemanticSearchVO {

    /** 来源文档 ID。 */
    private final Long documentId;

    /** 来源知识库 ID（供前端定位/跳转，Phase 11 按此做权限裁剪）。 */
    private final Long knowledgeBaseId;

    /** 来源文件名（前端展示用）。 */
    private final String fileName;

    /** 块在文档内的序号（从 0 开始）。 */
    private final int chunkIndex;

    /** 块文本内容（检索命中的原文，前端展示给用户看）。 */
    private final String chunkText;

    /**
     * 相似度分数（余弦相似度，越高越相关）。
     *
     * <p>为什么可能为 null？相似度分数由底层向量存储实现决定是否返回——
     * Spring AI 2.0 的 {@code VectorStoreRetriever.similaritySearch(SearchRequest)}
     * 返回 {@code List<Document>}，ES 实现会把分数写入 {@code Document.score}；
     * 但其他向量库实现可能不携带分数。业务代码按「可能为 null」处理，
     * 不假设任何实现一定会给分数，保证可移植性。</p>
     */
    private final Double score;

    private SemanticSearchVO(Long documentId, Long knowledgeBaseId, String fileName,
                             int chunkIndex, String chunkText, Double score) {
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.knowledgeBaseId = knowledgeBaseId;
        this.fileName = fileName;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
        this.score = score;
    }

    /**
     * 由检索命中的块构造响应 VO。
     *
     * @param documentId     来源文档 ID
     * @param knowledgeBaseId 来源知识库 ID
     * @param fileName       来源文件名
     * @param chunkIndex     块序号
     * @param chunkText      块文本
     * @param score          相似度分数（可为 null）
     * @return 语义搜索结果 VO
     */
    public static SemanticSearchVO of(Long documentId, Long knowledgeBaseId, String fileName,
                                      int chunkIndex, String chunkText, Double score) {
        return new SemanticSearchVO(documentId, knowledgeBaseId, fileName, chunkIndex, chunkText, score);
    }
}
