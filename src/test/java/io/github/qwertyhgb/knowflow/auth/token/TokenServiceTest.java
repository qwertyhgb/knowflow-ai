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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TokenService 单元测试：只验证学习版最核心的创建、查询与撤销流程。
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final Duration TTL = Duration.ofDays(7);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(redisTemplate, TTL);
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
}
