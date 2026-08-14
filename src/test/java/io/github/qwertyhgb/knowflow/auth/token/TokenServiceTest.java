package io.github.qwertyhgb.knowflow.auth.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TokenService 单元测试：只验证学习版最核心的创建、查询、撤销与续期流程。
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final Duration TTL = Duration.ofDays(7);

    private static final Duration RENEW_THRESHOLD = Duration.ofDays(1);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(redisTemplate, TTL, RENEW_THRESHOLD);
    }

    @Test
    void shouldCreateTokenAndStoreLoginState() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String token = tokenService.createToken(42L);

        assertEquals(32, token.length());
        verify(valueOperations).set("auth:token:" + token, "42", TTL);
    }

    @Test
    void shouldResolveAndRevokeToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:token:known-token")).thenReturn("42");

        assertEquals(42L, tokenService.resolveUserId("known-token").orElseThrow());
        tokenService.revokeToken("known-token");

        verify(redisTemplate).delete("auth:token:known-token");
    }

    @Test
    void shouldReturnEmptyWhenTokenDoesNotExist() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:token:missing-token")).thenReturn(null);

        assertTrue(tokenService.resolveUserId("missing-token").isEmpty());
    }

    @Test
    void shouldRenewTokenWhenRemainingTtlBelowThreshold() {
        // 剩余 30 分钟 < 阈值 1 天，应触发续期。
        when(redisTemplate.getExpire("auth:token:near-expiry")).thenReturn(1800L);

        tokenService.renewToken("near-expiry");

        verify(redisTemplate).expire("auth:token:near-expiry", TTL);
    }

    @Test
    void shouldNotRenewTokenWhenRemainingTtlAboveThreshold() {
        // 剩余 2 天 > 阈值 1 天，不应续期。
        when(redisTemplate.getExpire("auth:token:fresh")).thenReturn(Duration.ofDays(2).toSeconds());

        tokenService.renewToken("fresh");

        verify(redisTemplate, never()).expire(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void shouldNotRenewTokenWhenRemainingTtlEqualsThreshold() {
        when(redisTemplate.getExpire("auth:token:boundary"))
                .thenReturn(RENEW_THRESHOLD.toSeconds());

        tokenService.renewToken("boundary");

        verify(redisTemplate, never()).expire(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void shouldNotRenewTokenWhenKeyDoesNotExist() {
        // -2 表示 key 不存在，不应续期也不应报错。
        when(redisTemplate.getExpire("auth:token:gone")).thenReturn(-2L);

        tokenService.renewToken("gone");

        verify(redisTemplate, never()).expire(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void shouldRejectNonPositiveTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenService(redisTemplate, Duration.ZERO, RENEW_THRESHOLD));
    }

    @Test
    void shouldRejectRenewThresholdNotBelowTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenService(redisTemplate, TTL, TTL));
    }
}
