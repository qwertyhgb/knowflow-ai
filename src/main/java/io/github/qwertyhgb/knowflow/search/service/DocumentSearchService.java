package io.github.qwertyhgb.knowflow.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseMembershipChecker;
import io.github.qwertyhgb.knowflow.knowledge.service.KnowledgeBaseService;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseVO;
import io.github.qwertyhgb.knowflow.search.entity.DocumentIndex;
import io.github.qwertyhgb.knowflow.search.vo.DocumentSearchVO;
import io.github.qwertyhgb.knowflow.search.vo.SearchResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightFieldParameters;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 企业级文档搜索服务。
 *
 * <p><strong>搜索链路</strong>：</p>
 * <pre>
 * 请求 → 成员校验（企业存在 + 正常成员）→ 取可见知识库集合 → ES bool(filter+must) → 高亮/分页 → VO
 * </pre>
 *
 * <p><strong>安全声明</strong>：PRIVATE 知识库的内容绝不进入无权限用户的搜索结果——
 * 查询通过 {@link EnterpriseMembershipChecker} 先校验成员身份，再用
 * {@link KnowledgeBaseService#listVisibleKnowledgeBases} 取可见知识库列表，
 * ES 查询的 filter 子句显式限定 {@code enterpriseId} 与 {@code knowledgeBaseId IN (可见集合)}，
 * 从业务层与数据层双层防御。</p>
 *
 * <p><strong>教学要点</strong>：</p>
 * <ul>
 *   <li>可见性过滤是安全底线——搜索必须限定「当前用户可见的知识库集合」；
 *       不加此过滤，任何企业成员都能搜到 PRIVATE 知识库的内容，这是多租户数据泄露事故。</li>
 *   <li>filter vs query：权限条件永远放在 filter 子句（不参与相关度打分、可被 ES 缓存），
 *       关键词搜索放在 must 子句（参与 score 计算）。</li>
 *   <li>multi_match 多字段同时匹配：content 是核心搜索字段、fileName 标题匹配加分。</li>
 *   <li>高亮是 ES 在响应里标注命中词位置，前端可直接渲染 {@code <em>} 标签。</li>
 *   <li>{@code totalHits} 是近似值，精确计数有成本——面试常见问题。</li>
 *   <li>ES 深分页（from 过大）性能差，真实系统用 search_after。</li>
 *   <li>关键词内容不落日志——用户自由文本，遵循日志白名单原则。</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentSearchService {

    private final ElasticsearchOperations operations;

    private final EnterpriseMembershipChecker membershipChecker;

    private final KnowledgeBaseService knowledgeBaseService;

    public DocumentSearchService(ElasticsearchOperations operations,
                                 EnterpriseMembershipChecker membershipChecker,
                                 KnowledgeBaseService knowledgeBaseService) {
        this.operations = operations;
        this.membershipChecker = membershipChecker;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 跨知识库搜索可见文档。
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 目标企业 ID
     * @param keyword      搜索关键词（不能为空或空白）
     * @param page         页码（从 1 开始）
     * @param size         每页大小（1~100）
     * @return 搜索结果分页 VO
     */
    @Transactional(readOnly = true)
    public SearchResultVO searchDocuments(Long userId, Long enterpriseId, String keyword,
                                          int page, int size) {
        // 1. 先资源后权限：企业必须存在，操作者必须是该企业正常成员。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 关键词去除首尾空白后校验是否为空。
        //    选择 SEARCH_KEYWORD_REQUIRED 而非 INVALID_PARAMETER 作为错误码：前者语义精确，
        //    前端可据此在搜索框上显示「关键词不能为空」的提示；后者是通用参数校验错误码，
        //    前端无法区分是「关键词为空」还是「其他参数格式错」，对用户不友好。
        String strippedKeyword = keyword == null ? null : keyword.strip();
        if (strippedKeyword == null || strippedKeyword.isEmpty()) {
            throw new BusinessException(ErrorCode.SEARCH_KEYWORD_REQUIRED);
        }

        // 3. 取当前用户在该企业可见的知识库 ID 列表。
        //    复用 KnowledgeBaseService.listVisibleKnowledgeBases，保证可见性规则单一来源。
        List<KnowledgeBaseVO> visibleKbs = knowledgeBaseService.listVisibleKnowledgeBases(userId, enterpriseId);
        if (visibleKbs.isEmpty()) {
            // 没有可见知识库就不必发起查询，省一次 ES IO。
            // 这不是异常——企业可能还没有任何知识库或用户没有任何可见知识库，都是正常状态。
            log.info("event=document_searched enterpriseId={} keywordLength={} visibleKbCount=0 hitCount=0",
                    enterpriseId, strippedKeyword.length());
            return new SearchResultVO(List.of(), 0, page, size);
        }
        List<Long> visibleKbIds = visibleKbs.stream()
                .map(KnowledgeBaseVO::getId)
                .toList();

        // 4. 构建 ES bool 查询。
        //
        //    查询结构：
        //      bool
        //        filter: [term enterpriseId, terms knowledgeBaseId IN (可见集合)]
        //        must:   multi_match(keyword, content, fileName)
        //
        //    filter 条件只做布尔过滤，不影响 score，且可被 ES 缓存，适合权限条件。
        //    must 条件是关键词匹配，参与相关度打分。
        Query filterQuery = QueryBuilders.bool(b -> b
                .must(QueryBuilders.term(t -> t
                        .field("enterpriseId")
                        .value(enterpriseId)))
                .must(QueryBuilders.terms(t -> t
                        .field("knowledgeBaseId")
                        .terms(ts -> ts.value(visibleKbIds.stream()
                                .map(id -> co.elastic.clients.elasticsearch._types.FieldValue.of(id))
                                .toList())))));

        // multi_match 多字段同时匹配：content 是核心搜索字段，fileName 标题匹配加分。
        Query mustQuery = QueryBuilders.multiMatch(m -> m
                .fields("content", "fileName")
                .query(strippedKeyword));

        // 组装 NativeQuery（SDE 6.x 的 NativeQueryBuilder API）。
        // 教学注释：from = (page-1) * size（ES 分页从 0 开始）；ES 深分页（from 过大）
        // 性能差——真实系统用 search_after 替代，本步先学基础分页。
        // 高亮：使用 SDE 的 HighlightField + HighlightQuery 构建。
        HighlightFieldParameters fieldParams = HighlightFieldParameters.builder()
                .withPreTags("<em>")
                .withPostTags("</em>")
                .withFragmentSize(150)
                .build();
        HighlightField contentField = new HighlightField("content", fieldParams);
        HighlightField fileNameField = new HighlightField("fileName", fieldParams);
        Highlight highlight = new Highlight(List.of(contentField, fileNameField));
        HighlightQuery highlightQuery = new HighlightQuery(highlight, DocumentIndex.class);

        NativeQueryBuilder queryBuilder = NativeQuery.builder()
                .withQuery(QueryBuilders.bool(b -> b
                        .filter(filterQuery)
                        .must(mustQuery)))
                .withPageable(PageRequest.of(page - 1, size))
                .withHighlightQuery(highlightQuery);

        NativeQuery query = queryBuilder.build();
        SearchHits<DocumentIndex> searchHits = operations.search(query, DocumentIndex.class);

        // 5. 组装 VO：每条 hit 取高亮片段（getHighlightField），无高亮用 content 截断 200 字符。
        List<DocumentSearchVO> items = new ArrayList<>(searchHits.getSearchHits().size());
        for (var hit : searchHits.getSearchHits()) {
            items.add(DocumentSearchVO.from(hit));
        }

        // totalHits 是近似值（ES 精确计数有额外成本），学习阶段使用近似值即可。
        long total = searchHits.getTotalHits();

        // 只记录系统标识与统计信息，不记录关键词内容（用户自由文本，日志白名单原则）。
        log.info("event=document_searched enterpriseId={} keywordLength={} visibleKbCount={} hitCount={}",
                enterpriseId, strippedKeyword.length(), visibleKbIds.size(), total);

        return new SearchResultVO(items, total, page, size);
    }
}