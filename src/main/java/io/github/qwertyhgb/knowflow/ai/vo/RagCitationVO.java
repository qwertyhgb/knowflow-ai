package io.github.qwertyhgb.knowflow.ai.vo;

import lombok.Getter;

import java.util.Objects;

/**
 * RAG 引用来源对象（回答中 [序号] 标注对应的真实资料块）。
 *
 * <p><strong>引用是 RAG 的信任基石</strong>：LLM 生成的回答可能是对的，也可能出错，
 * 但只要有引用标注，用户就能点开「查看原文」核实、前端就能跳转到来源文档——
 * 回答的每个断言都可溯源，这是 RAG 相比「闭卷」纯 LLM 问答的关键价值。</p>
 *
 * <p>字段与 {@link SemanticSearchVO} 一一对应，直接由检索命中的块映射而来；
 * 序号（[1][2]...）与 answer 中的引用标注一一对应，对应关系由拼装顺序保证
 * （见 {@code RagChatServiceImpl} 的上下文编号）。</p>
 */
@Getter
public class RagCitationVO {

    /** 来源文档 ID（前端跳转文档详情用）。 */
    private final Long documentId;

    /** 来源知识库 ID（前端跳转知识库用，Phase 11 后续做权限裁剪）。 */
    private final Long knowledgeBaseId;

    /** 来源文件名（前端展示来源）。 */
    private final String fileName;

    /** 块在文档内的序号（从 0 开始）。 */
    private final int chunkIndex;

    /** 块文本内容（引用对应的原文，前端可展开查看）。 */
    private final String chunkText;

    /** 相似度分数（余弦相似度，可为 null——存储实现可能不返回分数）。 */
    private final Double score;

    private RagCitationVO(Long documentId, Long knowledgeBaseId, String fileName,
                          int chunkIndex, String chunkText, Double score) {
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.knowledgeBaseId = knowledgeBaseId;
        this.fileName = fileName;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
        this.score = score;
    }

    /**
     * 由检索命中的语义搜索 VO 构造引用 VO（字段一一对应直接透传）。
     *
     * @param documentId      来源文档 ID
     * @param knowledgeBaseId 来源知识库 ID
     * @param fileName        来源文件名
     * @param chunkIndex      块序号
     * @param chunkText       块文本
     * @param score           相似度分数（可为 null）
     * @return 引用来源 VO
     */
    public static RagCitationVO of(Long documentId, Long knowledgeBaseId, String fileName,
                                   int chunkIndex, String chunkText, Double score) {
        return new RagCitationVO(documentId, knowledgeBaseId, fileName, chunkIndex, chunkText, score);
    }
}
