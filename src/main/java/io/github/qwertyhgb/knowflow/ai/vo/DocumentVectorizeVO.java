package io.github.qwertyhgb.knowflow.ai.vo;

import lombok.Getter;

import java.util.Objects;

/**
 * 文档向量化响应对象。
 *
 * <p>承载向量化结果摘要，遵循项目 VO 规范：VO 只用于 Controller → 客户端，
 * 不用于数据库持久化；通过静态工厂 {@link #of} 构造对象，隐藏构造器并强制
 * 所有调用方走统一入口。</p>
 */
@Getter
public class DocumentVectorizeVO {

    /** 已向量化的文档 ID。 */
    private final Long documentId;

    /** 切块数量（一个块 = 一个向量）。 */
    private final int chunkCount;

    /** 向量维度（由 embedding 模型决定，bge-m3 为 1024）。 */
    private final int vectorSize;

    private DocumentVectorizeVO(Long documentId, int chunkCount, int vectorSize) {
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.chunkCount = chunkCount;
        this.vectorSize = vectorSize;
    }

    /**
     * 由向量化结果构造响应 VO。
     *
     * @param documentId 文档 ID
     * @param chunkCount 切块数量
     * @param vectorSize 向量维度
     * @return 向量化响应 VO
     */
    public static DocumentVectorizeVO of(Long documentId, int chunkCount, int vectorSize) {
        return new DocumentVectorizeVO(documentId, chunkCount, vectorSize);
    }
}
