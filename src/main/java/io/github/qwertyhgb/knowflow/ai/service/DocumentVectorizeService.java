package io.github.qwertyhgb.knowflow.ai.service;

import io.github.qwertyhgb.knowflow.ai.vo.DocumentVectorizeVO;

/**
 * 文档向量化服务接口。
 *
 * <p>职责：把已解析完成的文档内容（document.content）切块并逐块向量化，
 * 存入 Elasticsearch 向量索引，供 Phase 11 的 RAG 语义检索使用。</p>
 */
public interface DocumentVectorizeService {

    /**
     * 向量化指定文档。
     *
     * <p>流程：查文档 → 校验 READY + 内容非空 → 按文档 ID 删除旧向量（幂等）→
     * 切块 → 逐块向量化 → 批量写入 ES。详见实现类注释。</p>
     *
     * @param documentId 目标文档 ID
     * @return 向量化结果摘要（文档 ID、切块数、向量维度）
     */
    DocumentVectorizeVO vectorize(Long documentId);
}
