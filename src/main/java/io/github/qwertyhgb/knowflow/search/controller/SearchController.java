package io.github.qwertyhgb.knowflow.search.controller;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.search.service.DocumentSearchService;
import io.github.qwertyhgb.knowflow.search.vo.SearchResultVO;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业级搜索接口（跨知识库搜索可见文档）。
 *
 * <p>搜索结果是当前用户可见的知识库范围内的文档，PRIVATE 知识库的内容绝不进入
 * 无权限用户的搜索结果。不加 {@code @PreAuthorize}（搜索是成员基础能力，权限分层模型：
 * 权限码无法携带资源 ID，资源级可见性由 Service 按 {@code listVisibleKnowledgeBases}
 * 判定）。</p>
 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/search")
public class SearchController {

    private final DocumentSearchService documentSearchService;

    public SearchController(DocumentSearchService documentSearchService) {
        this.documentSearchService = documentSearchService;
    }

    /**
     * 企业级搜索：跨知识库全文搜索，结果仅限当前用户可见的知识库。
     *
     * @param authentication 当前登录用户认证信息
     * @param enterpriseId   目标企业 ID（路径变量）
     * @param keyword        搜索关键词（不能为空）
     * @param page           页码，从 1 开始（默认 1）
     * @param size           每页大小，1~100（默认 10）
     * @return 搜索结果分页 VO
     */
    @GetMapping
    public Result<SearchResultVO> search(Authentication authentication,
                                         @PathVariable Long enterpriseId,
                                         @RequestParam("keyword") String keyword,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();

        // 分页参数校验：page >= 1，size 在 1~100 之间。
        if (page < 1) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "页码必须大于等于 1");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "每页大小必须在 1~100 之间");
        }

        SearchResultVO result = documentSearchService.searchDocuments(
                userId, enterpriseId, keyword, page, size);
        return Result.success(result);
    }
}