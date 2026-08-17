package io.github.qwertyhgb.knowflow.auth.token;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 Redis 的登录态 Token 管理。
 *
 * <p>学习版只保留最核心的流程：登录时生成随机 Token，以
 * {@code auth:token:{token}} 为键保存用户 ID 和过期时间；认证时查询；登出时删除。</p>
 *
 * <p>当前只有 Redis 一种实现，因此直接使用具体类，不再额外拆分接口与实现类。</p>
 *
 * <p><strong>自动续期：</strong>认证流程会调用 {@link #renewToken(String)} 检查剩余 TTL，
 * 不足 {@code token-renew-threshold}（默认 1 天）时重置为完整 TTL，
 * 实现「活跃用户保持登录、不活跃用户自然过期」。续期使用 {@code EXPIRE} 命令只刷新过期时间，
 * 不重写 value，比 {@code SET} 更轻量。</p>
 */
@Service
public class TokenService {

    /** Redis Key 前缀，用于和其他业务数据区分。 */
    private static final String KEY_PREFIX = "auth:token:";

    private final StringRedisTemplate redisTemplate;

    /** Token 有效期来自配置，默认七天。 */
    private final Duration ttl;

    /** 续期阈值：剩余 TTL 小于此值才触发续期，避免每次请求都写 Redis。 */
    private final Duration renewThreshold;

    /**
     * 构造器，注入 Redis 模板和配置参数，并在启动时校验参数合法性。
     *
     * <p>三个校验条件都是防御性编程：</p>
     * <ul>
     *   <li>TTL 必须为正数：零或负值意味着 Token 创建即过期，登录态无法使用。</li>
     *   <li>续期阈值必须为正数：零或负值意味着「一直不续期」或「参数配置错误」。</li>
     *   <li>阈值必须小于 TTL：否则每次请求剩余 TTL 都小于阈值，导致每次请求都续期，
     *       失去「减少 Redis 写操作」的优化意义，且会刷掉自然过期机制。</li>
     * </ul>
     *
     * <p>这些校验在 Spring 容器启动时执行，配置错误可以尽早暴露，而不是等到用户登录
     * 时才发现 Token 永远无法创建或续期。</p>
     */
    public TokenService(StringRedisTemplate redisTemplate,
                        @Value("${knowflow.auth.token-ttl:7d}") Duration ttl,
                        @Value("${knowflow.auth.token-renew-threshold:1d}") Duration renewThreshold) {
        // ========== 校验 1：Token 有效期必须为正 ==========
        // 零或负值意味着 Token 创建即过期，登录完全不可用，属于配置错误。
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Token TTL must be positive");
        }
        // ========== 校验 2：续期阈值必须为正 ==========
        // 零或负值会导致续期逻辑永远不触发（renewThreshold <= 0 < remainingSeconds 恒成立），
        // 或者配置参数缺失（null），这两种情况都不应该静默运行。
        if (renewThreshold == null || renewThreshold.isZero() || renewThreshold.isNegative()) {
            throw new IllegalArgumentException("Token renew threshold must be positive");
        }
        // ========== 校验 3：阈值必须小于完整 TTL ==========
        // 如果阈值 >= TTL，意味着 Token 创建后「剩余时间」始终小于等于阈值，
        // 每次请求都会触发续期，Redis 每次请求都写一次 expire，失去了「减少写操作」的意义。
        // 正确配置示例：TTL=7天，阈值=1天 → 前 6 天不续期，最后 1 天续一次。
        if (renewThreshold.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException("Token renew threshold must be less than TTL");
        }
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
        this.renewThreshold = renewThreshold;
    }

    /**
     * 创建 Token，并把 Token 对应的用户 ID 保存到 Redis。
     *
     * <p>流程：先用 {@link UUID} 生成一个全局唯一的随机字符串作为 Token，
     * 去掉其中的连字符 {@code -} 让 Token 更短、更干净（去掉后仍是 32 位十六进制随机串，
     * 碰撞概率可忽略）。然后把「用户 ID」作为 value 写入 Redis，并一次性设置过期时间。</p>
     *
     * <p>{@code opsForValue().set(key, value, ttl)} 是 Redis 的
     * {@code SET key value EX seconds} 语义：写入和设置过期时间是同一条命令，
     * 不会出现「先写值、再设过期」之间进程崩溃导致永久不过期的问题。</p>
     *
     * @param userId 登录成功的用户 ID
     * @return 生成的 Token 字符串，后续请求需携带它来证明身份
     */
    public String createToken(Long userId) {
        // ========== 生成全局唯一 Token ==========
        // UUID 是标准的唯一标识符生成方案，版本 4 使用随机数，128 位长度。
        // 去掉连字符后得到 32 位十六进制随机字符串，作为登录凭证返回给客户端：
        //   - 原始 UUID：550e8400-e29b-41d4-a716-446655440000
        //   - 去掉连字符：550e8400e29b41d4a716446655440000
        // 去掉连字符后更短、更干净，且不影响唯一性（碰撞概率约 1/2^122）。
        // 为什么不用自增 ID 或 JWT？——学习阶段保持简单，随机 Token 足够好理解。
        String token = UUID.randomUUID().toString().replace("-", "");

        // ========== 写入 Redis ==========
        // key 格式：auth:token:{token}，前缀用于隔离业务数据，避免 key 冲突。
        // value 存 userId.toString()，因为 StringRedisTemplate 只处理字符串。
        // 第三个参数 ttl 是过期时间，由配置 knowflow.auth.token-ttl 控制（默认 7 天）。
        // Redis SET 命令的 EX 选项是原子操作：写入和设置过期时间一步完成，
        // 不会出现「SET 后进程崩溃导致 key 永久不过期」的竞态问题。
        redisTemplate.opsForValue().set(key(token), userId.toString(), ttl);

        // ========== 返回 Token ==========
        // 返回明文 Token 给客户端，客户端后续请求在 Authorization 请求头中携带它。
        // 注意：Token 本身不编码任何用户信息，查询用户 ID 需要查 Redis——这是「随机 Token」
        // 方案的核心特征（与 JWT 自包含信息的设计相反）。
        return token;
    }

    /**
     * 查询 Token 对应的用户 ID；不存在或已过期时返回空。
     *
     * <p>为什么返回 {@link Optional} 而不是 {@code null}：登录态可能随时失效
     * （过期、被登出），「查不到」是正常业务分支而非异常。用 {@code Optional}
     * 能强制调用方显式处理「未登录」的情况，避免到处写 {@code == null} 判断。</p>
     *
     * <p>注意 Redis 里所有 value 都是字符串，所以这里读出的是字符串，
     * 再用 {@code Long.valueOf} 还原成用户 ID 的数字类型。</p>
     *
     * @param token 客户端携带的 Token；为 null 或空白时直接视为未登录
     */
    public Optional<Long> resolveUserId(String token) {
        // ========== 参数校验：空 Token 直接返回 ==========
        // 调用方可能传 null 或空白字符串（如请求头 Authorization: Bearer   ），
        // 这种情况下不可能对应任何用户，直接返回空，避免不必要的 Redis 查询。
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        // ========== Redis 查询 ==========
        // 执行 GET auth:token:{token}，返回 userId 的字符串形式。
        // 三种可能的结果：
        //   1) 返回字符串 → Token 有效，调用方已登录
        //   2) 返回 null → key 不存在（从未创建、已过期、或已被登出删除）
        //   3) 返回空字符串 → 理论上不会发生（set 时设的是非空字符串），但按 null 处理
        String userId = redisTemplate.opsForValue().get(key(token));
        // 查不到（null）时返回 Optional.empty()，让调用方感知「未登录」状态，
        // 而不是返回 null 导致调用方忘记判空引发 NullPointerException。
        return userId == null ? Optional.empty() : Optional.of(Long.valueOf(userId));
    }

    /**
     * 删除 Token 对应的 Redis 登录态，实现登出。
     *
     * <p>登出本质就是让这个 Token 失效：把 Redis 里的 key 删掉后，
     * 后续 {@link #resolveUserId(String)} 查不到，认证自然失败。</p>
     *
     * @param token 待登出的 Token；为 null 或空白时跳过，避免误删一个非法 key
     */
    public void revokeToken(String token) {
        // ========== 参数校验：空 Token 直接跳过 ==========
        // 登出操作应当「幂等」：即使 Token 已经过期或被删过，再次调用也不应报错。
        // 空 Token 更不可能存在对应的 key，直接跳过，避免误删 Redis 中其他业务 key。
        if (token != null && !token.isBlank()) {
            // ========== 删除 Redis key ==========
            // 执行 DEL auth:token:{token}，删除对应的登录态。
            // 删除后，后续请求携带此 Token 时 resolveUserId 会返回 Optional.empty()，
            // 认证过滤器会跳过认证，请求被 RestAuthenticationEntryPoint 拒绝（401）。
            // 这里不关心 delete 返回值（成功删了多少个 key），登出操作本身是幂等的。
            redisTemplate.delete(key(token));
        }
    }

    /**
     * 按需续期：剩余 TTL 小于阈值时重置为完整 TTL。
     *
     * <p>目的：用户持续访问时，登录态不会在「第 7 天」突然失效；
     * 但只要超过阈值时间没有任何请求，Token 就会自然过期，无需手动清理。</p>
     *
     * <p>Redis 的 {@code getExpire(key)} 返回值有三种情况，需要分别处理：
     * <ul>
     *   <li>{@code > 0}：剩余有效秒数（最常见的情况）</li>
     *   <li>{@code -1}：key 存在，但当初没设过期时间（本流程不会出现，但兜底也续一下，补上过期时间）</li>
     *   <li>{@code -2}：key 不存在（已过期或被登出），无需续期，直接跳过</li>
     * </ul>
     * 续期用 {@code expire(key, ttl)} 只刷新过期时间、不重写 value，
     * 比 {@code set} 少一次写值操作，更轻量。</p>
     *
     * @param token 待续期的 Token；为 null 或空白时直接跳过
     */
    public void renewToken(String token) {
        // ========== 参数校验：空 Token 直接跳过 ==========
        // 没有 Token 就谈不上续期，直接返回，不做任何操作。
        if (token == null || token.isBlank()) {
            return;
        }

        // ========== 查询 Redis 剩余过期时间 ==========
        // TTL 命令返回 key 的剩余存活秒数，返回值有三种情况：
        //   > 0  → 剩余秒数，正常值
        //   -1  → key 存在但无过期时间（本流程不会出现，因为 SET 时指定了 EX）
        //   -2  → key 不存在（已过期自动删除，或已被登出时手动删除）
        String redisKey = key(token);
        Long remainingSeconds = redisTemplate.getExpire(redisKey);

        // ========== 判断是否需要续期 ==========
        // 情况 1：getExpire 返回 null（Redis 连接异常）或 -2（key 已不存在）
        //         → 无法获取或已失效，都不适合续期，直接跳过。
        //         注意：null 来自 Redis 命令异常，此时不应抛异常影响主流程，
        //         调用方（TokenAuthenticationFilter）会捕获 RuntimeException 并记日志。
        if (remainingSeconds == null || remainingSeconds == -2L) {
            return;
        }

        // 情况 2：remainingSeconds < 0（即 -1） → key 存在但无过期时间。
        //         虽然本流程不会出现这种情况，但作为兜底处理，补一个过期时间。
        // 情况 3：remainingSeconds < renewThreshold（阈值，默认 1 天）
        //         → 剩余时间不多了，重置为完整 TTL（默认 7 天），让用户继续保持登录。
        // 情况 4：remainingSeconds >= renewThreshold
        //         → 剩余时间还充裕，什么都不做，避免每次请求都写 Redis。
        //
        // 效果：用户每天至少访问一次，Token 永远不过期；超过 1 天不访问，Token 自然过期。
        if (remainingSeconds < 0 || remainingSeconds < renewThreshold.toSeconds()) {
            // ========== 执行续期 ==========
            // 使用 EXPIRE 命令而非 SET 命令：EXPIRE 只刷新过期时间，不重写 value，
            // 比 SET 少一次「写值」操作，更轻量。而且续期本来就不需要改 value。
            // 这里不关心 expire 返回值（true/false），续期是「尽力而为」的优化。
            redisTemplate.expire(redisKey, ttl);
        }
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
