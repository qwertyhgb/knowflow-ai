package io.github.qwertyhgb.knowflow.ai.rate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 基于 Redis 固定窗口计数的用户维限流服务。
 *
 * <p><strong>为什么 AI 接口需要限流（业务动机）</strong>：AI 对话/RAG 的每次调用都会
 * 请求付费的外部大模型（按 token 计费）。不限流意味着：恶意脚本、前端 bug 导致的
 * 死循环、正常用户的误操作，都会把费用刷爆。按「用户」维度限流（每个用户每分钟
 * 最多 {@link #AI_CHAT_LIMIT} 次），让单个用户的消耗有硬上限。</p>
 *
 * <p><strong>为什么固定窗口</strong>：用 Redis 的 {@code INCR} 计数，首次自增返回 1 时
 * 设置 60 秒 TTL——窗口结束键自动过期归零。实现极简、原子（INCR 单命令）；
 * 代价是窗口边界存在突刺（第 59.9 秒和第 60.1 秒各自能打满一窗），滑动窗口/令牌桶
 * 能消除突刺，但复杂度明显更高——学习阶段固定窗口足够，注释里说明即可。</p>
 *
 * <p><strong>为什么单独的 Service 而不是 Spring AOP 注解</strong>：AOP 切面是限流的
 * 常见实现（一个注解标记任意接口），但切面的隐式织入对学习者不直观、排障困难，
 * 而且它的参数提取（取 userId）依赖约定而非显式传参。当前作为独立小服务显式调用，
 * 调用链清晰可见，是 Phase 15（AOP/操作日志）要学习的内容——到时候再评估是否用
 * 注解收敛。学习优先原则：先显式、后抽象。</p>
 */
@Slf4j
@Service
public class RateLimitService {

    /** Redis 键前缀：与其他业务键（token/kb/lock）同风格，标注业务归属。 */
    private static final String KEY_PREFIX = "knowflow:ratelimit:";

    /** 时间窗口长度（秒）：60 秒一个计数窗口，窗口结束键自动过期归零。 */
    private static final long WINDOW_SECONDS = 60;

    /** AI 对话类接口：每个用户每分钟最多 20 次。 */
    public static final long AI_CHAT_LIMIT = 20;

    private final StringRedisTemplate redisTemplate;

    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取一次配额。
     *
     * @param userId 当前登录用户 ID（限流按用户维度，键含 userId）
     * @param action 动作标识（如 {@code ai_chat}、{@code ai_rag}）——不同动作独立计数，
     *               互不挤占：用户聊普通对话不会消耗 RAG 的配额
     * @return true = 允许执行（窗口内未超限）；false = 已超限，调用方应拒绝
     */
    public boolean tryAcquire(Long userId, String action) {
        // Redis 操作整体 try-catch：限流是「保护手段」不是「正确性依赖」——
        // Redis 故障时宁可放行（多花几分钱）也不误伤正常用户（接口 500）。
        // 与知识库缓存的降级是同一哲学：Redis 是增强，不是主链路。
        try {
            // key 形如 knowflow:ratelimit:ai_chat:{userId}：action 区分业务，userId 维度隔离用户。
            String key = KEY_PREFIX + action + ":" + userId;

            // ---- 固定窗口计数的核心两条命令 ----
            // 1) INCR：原子自增并返回计数。第一次返回 1（键不存在时 INCR 先建键再自增）。
            Long count = redisTemplate.opsForValue().increment(key);

            // 2) 首次自增（返回 1）时为键设置 60 秒过期——窗口结束键自动消失、计数归零，
            //    下一个窗口重新从 0 计。这就是「窗口」的实现：TTL 即窗口长度。
            //    【非原子性说明】INCR 与 EXPIRE 是两条独立命令：若首次 INCR 后进程在此处
            //    崩溃，EXPIRE 未执行，该键将无 TTL 残留（计数永久累积）。概率极低且后果
            //    仅是「该用户被多限流一段时间」，可接受；用 Lua 脚本把两条命令包成原子
            //    操作是进阶优化（引入脚本维护成本，学习阶段不做）。
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(WINDOW_SECONDS));
            }

            // 3) 计数 <= 阈值 → 放行；超过 → 拒绝。超限的请求计数继续增长无妨
            //    （窗口结束自然归零），这符合固定窗口「窗口内第 21 次起拒绝」的语义。
            return count == null || count <= AI_CHAT_LIMIT;
        } catch (RuntimeException ex) {
            // 日志白名单：只记动作与异常类型名，不记键中的用户 ID 与任何内容。
            log.warn("event=rate_limit_check_failed action={} errorType={}",
                    action, ex.getClass().getSimpleName());
            return true;
        }
    }
}