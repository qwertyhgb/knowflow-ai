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
 */
@Service
public class TokenService {

    /** Redis Key 前缀，用于和其他业务数据区分。 */
    private static final String KEY_PREFIX = "auth:token:";

    private final StringRedisTemplate redisTemplate;

    /** Token 有效期来自配置，默认七天。 */
    private final Duration ttl;

    public TokenService(StringRedisTemplate redisTemplate,
                        @Value("${knowflow.auth.token-ttl:7d}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
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

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
