package io.github.qwertyhgb.knowflow.knowledge.service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 知识库缓存键与 TTL 约定（Phase 14 Cache Aside）。
 *
 * <p>集中管理缓存键的生成规则与过期时间，避免散落在业务代码里造成命名漂移——
 * 一处约定、处处复用，改键格式只需改这一个类（Redis 是字符串键的世界，
 * 键的规范性直接影响排障与监控的可读性）。</p>
 *
 * <p><strong>为什么键必须同时含企业维度与用户维度：</strong></p>
 * <ul>
 *   <li><strong>企业维度</strong>（{@code enterpriseId}）：多租户隔离。不同企业的
 *       知识库可以有相同的自增 ID（如都叫 1 号库），键若不区分企业，A 企业的缓存
 *       会被 B 企业命中，造成跨租户串数据。</li>
 *   <li><strong>用户维度</strong>（{@code userId}）：可见性与 {@code myRole} 都是
 *       <strong>当前请求用户相关</strong>的——列表只返回「我可见」的知识库（成员或
 *       PUBLIC），详情里的 {@code myRole} 是「我的」成员角色。缓存键必须覆盖影响
 *       结果的全部输入：若只按企业分键，低权限用户会命中高权限用户留下的缓存
 *       （私有库泄露、角色串号）。这就是「缓存键的完备性」——漏掉任何一个决定
 *       结果的输入，缓存就会返回错误数据。</li>
 * </ul>
 *
 * <p><strong>TTL 为什么随机（防雪崩）：</strong>若大量键同时过期，过期瞬间全部
 * 请求一起打到数据库（缓存雪崩）。给 TTL 加随机抖动（{@link #randomTtl(long)}），
 * 让键的过期时间错开，数据库压力被摊平。教学阶段用随机抖动这一种手段即可，
 * 互斥重建/逻辑过期等击穿手段后续步骤再讲。</p>
 */
public final class KnowledgeBaseCacheKeys {

    private KnowledgeBaseCacheKeys() {
    }

    /** 知识库缓存键前缀：与 {@code auth:token:} 同风格，标志 Redis 中这条数据的业务归属。 */
    private static final String KEY_PREFIX = "knowflow:kb:";

    /** 列表键前缀。 */
    private static final String LIST_PREFIX = KEY_PREFIX + "list:";

    /** 详情键前缀。 */
    private static final String DETAIL_PREFIX = KEY_PREFIX + "detail:";

    // ==================== TTL 常量（秒） ====================

    /** 列表缓存基础 TTL：5 分钟。读多写少的数据，5 分钟内的短暂陈旧可接受。 */
    public static final long LIST_TTL_SECONDS = 300;

    /** 详情缓存基础 TTL：5 分钟，理由同列表。 */
    public static final long DETAIL_TTL_SECONDS = 300;

    /**
     * 空值缓存 TTL：1 分钟。
     *
     * <p>防缓存穿透用的「空结果」缓存（详情查不到、列表为空），TTL 刻意远短于
     * 正常数据——空值只是短暂挡枪，避免因某个 ID 被反复查询而长期占住内存；
     * 且空值不值得长缓存（万一数据马上出现，长 TTL 会造成更久的错误可见性）。</p>
     */
    public static final long EMPTY_TTL_SECONDS = 60;

    /**
     * TTL 随机抖动幅度：120 秒。
     *
     * <p>最终过期时间 = 基础 TTL + [0, 抖动幅度) 的随机秒数，使同批写入的键
     * 过期时间自然错开，防止「整批 key 同秒失效 → 数据库瞬间被打满」的雪崩。</p>
     */
    public static final long TTL_JITTER_SECONDS = 120;

    // ==================== 键生成 ====================

    /**
     * 知识库列表缓存键：按用户 + 企业隔离。
     *
     * <p>形如 {@code knowflow:kb:list:{enterpriseId}:{userId}}。列表结果是
     * 「当前用户在该企业下可见的知识库」，必须含 userId（见类注释）。</p>
     */
    public static String listKey(Long enterpriseId, Long userId) {
        return LIST_PREFIX + enterpriseId + ":" + userId;
    }

    /**
     * 知识库详情缓存键：按用户 + 企业 + 知识库隔离。
     *
     * <p>形如 {@code knowflow:kb:detail:{enterpriseId}:{knowledgeBaseId}:{userId}}。
     * 详情结果含「当前用户的 myRole」，必须含 userId（见类注释）。</p>
     */
    public static String detailKey(Long enterpriseId, Long knowledgeBaseId, Long userId) {
        return DETAIL_PREFIX + enterpriseId + ":" + knowledgeBaseId + ":" + userId;
    }

    // ==================== 失效用通配 Pattern ====================

    /**
     * 某企业下全部用户列表缓存键的通配模式。
     *
     * <p>形如 {@code knowflow:kb:list:{enterpriseId}:*}。写操作（创建/改名/状态变更/
     * 成员变动）会影响企业内<strong>多个用户</strong>的可见结果，而具体影响哪些用户
     * 无法低成本枚举——用通配模式让 {@code keys()/SCAN} 一次匹配该企业所有用户的
     * 列表键，统一失效。</p>
     */
    public static String listPattern(Long enterpriseId) {
        return LIST_PREFIX + enterpriseId + ":*";
    }

    /**
     * 某知识库全部用户详情缓存键的通配模式。
     *
     * <p>形如 {@code knowflow:kb:detail:{enterpriseId}:{knowledgeBaseId}:*}。
     * 知识库被改名/改权限/删成员后，所有用户看到的该库详情（含各自 myRole）都可能
     * 变化，同样按通配模式统一失效。</p>
     */
    public static String detailPattern(Long enterpriseId, Long knowledgeBaseId) {
        return DETAIL_PREFIX + enterpriseId + ":" + knowledgeBaseId + ":*";
    }

    // ==================== TTL 计算 ====================

    /**
     * 生成带随机抖动的过期时长：{@code 基础TTL + [0, TTL_JITTER_SECONDS]} 秒。
     *
     * <p>为什么用 {@code ThreadLocalRandom}：随机数只在当前线程使用，无需跨线程
     * 竞争共享种子，高并发下比 {@code Random} 性能更好且线程安全。</p>
     */
    public static Duration randomTtl(long baseSeconds) {
        long jitter = ThreadLocalRandom.current().nextLong(TTL_JITTER_SECONDS + 1);
        return Duration.ofSeconds(baseSeconds + jitter);
    }
}