package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.dto.request.DocumentVectorizeRequest;
import io.github.qwertyhgb.knowflow.ai.service.DocumentVectorizeService;
import io.github.qwertyhgb.knowflow.ai.vo.DocumentVectorizeVO;
import io.github.qwertyhgb.knowflow.common.response.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档向量化接口。
 *
 * <p>POST /api/ai/documents/vectorize —— 手动触发指定文档的向量化全链路：
 * 切块 → embedding → 写入 ES 向量索引。</p>
 *
 * <p>【为什么是手动触发？】
 * 本步先打通「解析完成（READY）→ 向量化 → 落库」的链路，用 HTTP 接口手动触发
 * 最直接、可验证。自动化（解析成功后自动入队向量化）是下一步的 MQ 主题——
 * 届时将复用本 Service 的核心逻辑，由消费者在后台调用。</p>
 *
 * <p>【安全边界】
 * 本接口与 AI 对话接口一样自动受 Security 保护（全局默认需登录），非企业作用域，
 * 无需 X-Enterprise-Id。日志只记录 documentId/chunkCount 等白名单字段，
 * 不记录文档内容原文。</p>
 */
@RestController
@RequestMapping("/api/ai/documents")
public class DocumentVectorizeController {

    private final DocumentVectorizeService documentVectorizeService;

    public DocumentVectorizeController(DocumentVectorizeService documentVectorizeService) {
        this.documentVectorizeService = documentVectorizeService;
    }

    /**
     * 手动触发指定文档的向量化。
     *
     * @param request 文档 ID（{@code @Valid} 触发 {@code @Positive} 校验，0/负数返回 400）
     * @return 向量化结果摘要（文档 ID、切块数、向量维度）
     */
    @PostMapping("/vectorize")
    public Result<DocumentVectorizeVO> vectorize(@Valid @RequestBody DocumentVectorizeRequest request) {
        DocumentVectorizeVO vo = documentVectorizeService.vectorize(request.getDocumentId());
        return Result.success(vo);
    }
}
