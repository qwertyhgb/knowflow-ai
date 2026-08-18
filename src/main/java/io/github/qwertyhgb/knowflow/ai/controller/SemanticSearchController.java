package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.dto.request.SemanticSearchRequest;
import io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService;
import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;
import io.github.qwertyhgb.knowflow.common.response.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 语义搜索接口。
 *
 * <p>POST /api/ai/search/semantic —— 按「意思」检索向量索引，返回与问题最相似的
 * Top K 文档块。与全文搜索（关键词精确匹配）互补：本接口不受措辞差异影响，
 * 搜「怎么改密码」也能命中「修改登录密码的步骤」。</p>
 *
 * <p>【接口语义——为什么是无状态查询？】
 * 本接口与 AI 对话/向量化接口一致：非企业作用域，登录即可用，不依赖企业上下文。
 * 用户可能跨企业检索问题（「如何提升系统查询速度」这类通用问题），按知识库的
 * <strong>权限过滤</strong>留给 Phase 11 的 RAG 阶段——届时结合企业上下文
 * （X-Enterprise-Id）裁剪可见的知识库，再拼接检索结果喂给 LLM。</p>
 *
 * <p>【为什么返回块而不是直接回答？】
 * 本步只做「检索」：把最相关的块连同来源信息返回给前端展示/跳转。
 * 把块拼进 Prompt 让 LLM 生成回答（RAG 完整链路）是 Phase 11 的主题。</p>
 */
@RestController
@RequestMapping("/api/ai/search")
public class SemanticSearchController {

    private final SemanticSearchService semanticSearchService;

    public SemanticSearchController(SemanticSearchService semanticSearchService) {
        this.semanticSearchService = semanticSearchService;
    }

    /**
     * 语义检索 Top K 块。
     *
     * @param request 问题 + Top K（{@code @Valid} 校验：question 非空且 ≤1000，topK 1~20）
     * @return Top K 块列表（含来源文档信息与相似度分数）
     */
    @PostMapping("/semantic")
    public Result<List<SemanticSearchVO>> search(@Valid @RequestBody SemanticSearchRequest request) {
        List<SemanticSearchVO> results = semanticSearchService.search(request.getQuestion(), request.resolveTopK());
        return Result.success(results);
    }
}
