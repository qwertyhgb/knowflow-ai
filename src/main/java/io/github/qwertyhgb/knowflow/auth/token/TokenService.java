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

    public TokenService(StringRedisTemplate redisTemplate,
                        @Value("${knowflow.auth.token-ttl:7d}") Duration ttl,
                        @Value("${knowflow.auth.token-renew-threshold:1d}") Duration renewThreshold) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Token TTL must be positive");
        }
        if (renewThreshold == null || renewThreshold.isZero() || renewThreshold.isNegative()) {
            throw new IllegalArgumentException("Token renew threshold must be positive");
        }
        // 阈值必须小于完整 TTL，否则 Token 在几乎每次请求时都会续期，失去阈值的意义。
        if (renewThreshold.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException("Token renew threshold must be less than TTL");
        }
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
        this.renewThreshold = renewThreshold;
    }

    /**
     * 创建 Token，并把 Token 对应的用户 ID 保存到 Redis。
     */
    public String createToken(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(key(token), userId.toString(), ttl);
        return token;
    }

    /**
     * 查询 Token 对应的用户 ID；不存在或已过期时返回空。
     */
    public Optional<Long> resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String userId = redisTemplate.opsForValue().get(key(token));
        return userId == null ? Optional.empty() : Optional.of(Long.valueOf(userId));
    }

    /**
     * 删除 Token 对应的 Redis 登录态，实现登出。
     */
    public void revokeToken(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(key(token));
        }
    }

    /**
     * 按需续期：剩余 TTL 小于阈值时重置为完整 TTL。
     *
     * <p>Redis {@code getExpire} 返回值含义：
     * <ul>
     *   <li>{@code >0}：剩余秒数</li>
     *   <li>{@code -1}：键存在但无过期时间（本场景不会出现，但安全起见也续一下）</li>
     *   <li>{@code -2}：键不存在（已过期或已登出），直接跳过</li>
     * </ul>
     * 续期用 {@code expire(key, ttl)} 只刷新过期时间，不重写 value，比 {@code set} 更轻量。
     *
     * @param token 待续期的 Token；为 null 或空白时直接跳过
     */
    public void renewToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String redisKey = key(token);
        Long remainingSeconds = redisTemplate.getExpire(redisKey);
        // -2 表示键不存在（已过期/已登出），无需续期。
        if (remainingSeconds == null || remainingSeconds == -2L) {
            return;
        }
        // 剩余 TTL 不足阈值才续期；-1（无过期时间）也走续期路径，补上过期时间。
        if (remainingSeconds < 0 || remainingSeconds < renewThreshold.toSeconds()) {
            redisTemplate.expire(redisKey, ttl);
        }
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
