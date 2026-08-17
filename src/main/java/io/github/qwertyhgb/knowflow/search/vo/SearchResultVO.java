package io.github.qwertyhgb.knowflow.search.vo;

import lombok.Getter;

import java.util.List;

/**
 * 搜索结果分页 VO。
 *
 * <p>包含搜索命中的文档条目列表与分页元数据 ES 的 {@code totalHits} 是近似值
 * （精确计数有成本——面试点），学习阶段使用近似值即可。</p>
 */
@Getter
public class SearchResultVO {

    /** 当前页的搜索结果条目。 */
    private final List<DocumentSearchVO> items;

    /** 总命中数（近似值，ES 精确计数有额外成本）。 */
    private final long total;

    /** 当前页码（从 1 开始）。 */
    private final int page;

    /** 每页大小。 */
    private final int size;

    public SearchResultVO(List<DocumentSearchVO> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}