package io.github.qwertyhgb.knowflow.common.response;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * 统一分页数据响应对象。
 *
 * <p>本类用于将「一页数据 + 分页元信息」封装成一个不可变对象，作为 {@link Result} 的
 * {@code data} 字段返回给前端。典型用法如下：</p>
 *
 * <pre>{@code
 * PageResult<User> page = PageResult.of(userList, totalCount, pageNum, pageSize);
 * return Result.success(page);
 * }</pre>
 *
 * <p>这样业务数据（{@link #getRecords()}）与分页元数据（{@link #getTotal()}、
 * {@link #getPageNum()}、{@link #getPageSize()} 等）就能保持一致的响应结构，
 * 前端无需关心后端如何分页，只读约定好的字段即可。</p>
 *
 * <h2>设计约定</h2>
 * <ul>
 *   <li><strong>不可变</strong>：所有字段均为 {@code final}，构造完成后不可修改；
 *       传入的列表会被 {@link List#copyOf} 拷贝成不可变列表，避免外部对原列表的修改
 *       反向污染分页结果。</li>
 *   <li><strong>页码从 1 开始</strong>：{@link #pageNum} 最小值为 1，与大多数前端分页
 *       组件的习惯一致，而非从 0 开始。</li>
 *   <li><strong>参数严格校验</strong>：{@code total} 不允许为负，{@code pageNum} 与
 *       {@code pageSize} 必须大于 0，非法值会在构造时直接抛出
 *       {@link IllegalArgumentException}，尽早暴露调用方的错误。</li>
 *   <li><strong>派生字段自动计算</strong>：{@link #totalPages}、{@link #hasNext}、
 *       {@link #hasPrevious} 均由 {@code total} / {@code pageNum} / {@code pageSize}
 *       推导得出，调用方无需手工计算，保证前后端口径一致。</li>
 * </ul>
 *
 * @param <T> 当前页记录的元素类型，例如 {@code PageResult<User>}
 */
@Getter
public final class PageResult<T> {

    /**
     * 当前页的记录列表。
     *
     * <p>始终为非 null、不可变（不可增删改）的列表。当查询结果为空时，返回空列表
     * {@code List.of()} 而不是 {@code null}，避免调用方做空指针判断。</p>
     */
    private final List<T> records;

    /**
     * 符合查询条件的记录总数（跨所有页，而非仅当前页）。
     *
     * <p>用于前端计算总页数、显示「共 X 条」。必须为非负整数。</p>
     */
    private final long total;

    /**
     * 当前页码，从 1 开始（第 1 页、第 2 页……）。
     *
     * <p>必须大于 0。前端通常基于此值结合 {@link #hasNext} / {@link #hasPrevious}
     * 决定「上一页 / 下一页」按钮的可用状态。</p>
     */
    private final long pageNum;

    /**
     * 每页的记录条数，必须大于 0。
     *
     * <p>由后端在查询时指定（通常来自请求参数或默认值），并非由本类推断。</p>
     */
    private final int pageSize;

    /**
     * 总页数。
     *
     * <p>由 {@code total} 和 {@code pageSize} 计算得出：当 {@code total == 0} 时为 0，
     * 否则为 {@code (total + pageSize - 1) / pageSize}（向上取整）。</p>
     */
    private final long totalPages;

    /**
     * 当前页之后是否还有下一页。
     *
     * <p>等价于 {@code pageNum < totalPages}，即还没翻到最后一页时为 {@code true}。</p>
     */
    private final boolean hasNext;

    /**
     * 当前页之前是否还有上一页。
     *
     * <p>等价于 {@code pageNum > 1}，即不是第一页时为 {@code true}。</p>
     */
    private final boolean hasPrevious;

    /**
     * 私有构造器：统一在此完成参数的校验与派生字段的计算。
     *
     * <p>外部应通过静态工厂方法 {@link #of(List, long, long, int)} 或
     * {@link #empty(long, int)} 创建实例，而非直接 {@code new}。</p>
     *
     * @param records  当前页记录列表，不允许为 null（内部会拷贝为不可变列表）
     * @param total    总记录数，不允许为负
     * @param pageNum  当前页码，从 1 开始，必须大于 0
     * @param pageSize 每页条数，必须大于 0
     * @throws NullPointerException     当 {@code records} 为 null 时
     * @throws IllegalArgumentException 当 {@code total} 为负、或 {@code pageNum} /
     *                                  {@code pageSize} 不大于 0 时
     */
    private PageResult(List<T> records, long total, long pageNum, int pageSize) {
        // List.copyOf 会返回一个真正不可变的列表，同时拒绝 null 元素，
        // 既防止外部修改，也避免 null 元素混入导致下游遍历时出现空指针。
        this.records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
        this.total = requireNonNegative(total, "total");
        this.pageNum = requirePositive(pageNum, "pageNum");
        // pageSize 对外声明为 int，但校验函数接收 long；先校验为正值，再安全窄化为 int。
        this.pageSize = Math.toIntExact(requirePositive(pageSize, "pageSize"));
        this.totalPages = calculateTotalPages(this.total, this.pageSize);
        this.hasNext = this.pageNum < this.totalPages;
        this.hasPrevious = this.pageNum > 1;
    }

    /**
     * 创建分页响应（静态工厂方法，推荐入口）。
     *
     * <p>接收任意 {@link List} 子类型作为记录来源，内部统一拷贝为不可变列表。</p>
     *
     * @param records  当前页记录列表，不允许为 null
     * @param total    总记录数，不允许为负
     * @param pageNum  当前页码，从 1 开始，必须大于 0
     * @param pageSize 每页条数，必须大于 0
     * @param <T>      记录的元素类型
     * @return 封装好的不可变分页对象
     * @throws NullPointerException     当 {@code records} 为 null 时
     * @throws IllegalArgumentException 当 {@code total} / {@code pageNum} / {@code pageSize}
     *                                  不满足约束时
     */
    public static <T> PageResult<T> of(List<? extends T> records, long total, long pageNum, int pageSize) {
        Objects.requireNonNull(records, "records must not be null");
        return new PageResult<>(List.copyOf(records), total, pageNum, pageSize);
    }

    /**
     * 创建「空分页」响应：records 为空列表、total 为 0。
     *
     * <p>适用于查询无结果、或查询条件本身不匹配任何记录的场景。此时
     * {@link #getTotalPages()} 为 0，{@link #hasNext()} 与 {@link #hasPrevious()}
     * 均为 {@code false}。</p>
     *
     * @param pageNum  当前页码，从 1 开始，必须大于 0
     * @param pageSize 每页条数，必须大于 0
     * @param <T>      记录的元素类型（空列表时由调用方显式指定，如
     *                 {@code PageResult.<User>empty(1, 20)}）
     * @return 空分页对象
     * @throws IllegalArgumentException 当 {@code pageNum} / {@code pageSize} 不大于 0 时
     */
    public static <T> PageResult<T> empty(long pageNum, int pageSize) {
        return of(List.of(), 0, pageNum, pageSize);
    }

    /**
     * 计算总页数：无记录时为 0，否则向上取整。
     *
     * <p>公式 {@code 1 + (total - 1) / pageSize} 是向上取整（ceil）的整数技巧，
     * 避免了浮点运算带来的精度问题。例如 total=10、pageSize=3 时：
     * {@code 1 + 9 / 3 = 4}，即 10 条记录按每页 3 条分，共 4 页。</p>
     *
     * @param total    总记录数（调用方已保证非负）
     * @param pageSize 每页条数（调用方已保证大于 0）
     * @return 总页数，total 为 0 时返回 0
     */
    private static long calculateTotalPages(long total, int pageSize) {
        return total == 0 ? 0 : 1 + (total - 1) / pageSize;
    }

    /**
     * 校验值非负，否则抛出 {@link IllegalArgumentException}。
     *
     * @param value 待校验的值
     * @param name  参数名，用于拼接异常信息，便于定位问题
     * @return 校验通过的原值
     * @throws IllegalArgumentException 当 {@code value < 0} 时
     */
    private static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    /**
     * 校验值为正数（严格大于 0），否则抛出 {@link IllegalArgumentException}。
     *
     * @param value 待校验的值
     * @param name  参数名，用于拼接异常信息，便于定位问题
     * @return 校验通过的原值
     * @throws IllegalArgumentException 当 {@code value <= 0} 时
     */
    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }
}
