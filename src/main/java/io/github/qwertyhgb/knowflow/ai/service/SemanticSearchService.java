package io.github.qwertyhgb.knowflow.ai.service;

import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;

import java.util.List;

/**
 * 语义搜索服务接口。
 *
 * <p>职责：把用户问题向量化，在 ES 向量索引中检索最相似的 Top K 块并返回。
 * 这是 Phase 10 的查询链路：Question → Embedding → Similarity Search → Top K Chunks。</p>
 */
public interface SemanticSearchService {

    /**
     * 语义检索：返回与问题最相似的 Top K 文档块。
     *
     * @param question 用户问题（自然语言）
     * @param topK     返回条数（调用方已保证在 1~20）
     * @return 按相似度降序的 Top K 块列表（含来源信息与相似度分数）
     */
    List<SemanticSearchVO> search(String question, int topK);
}
