package io.github.qwertyhgb.knowflow.auth.token;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Redis 登录态集成测试，仅在 {@code RUN_REDIS_TESTS=true} 时执行。
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
class RedisTokenIntegrationTest {

    @Autowired
    private TokenService tokenService;

    @Test
    void shouldCreateResolveAndRevokeToken() {
        String token = tokenService.createToken(42L);
        try {
            assertEquals(42L, tokenService.resolveUserId(token).orElseThrow());
        } finally {
            tokenService.revokeToken(token);
        }
        assertTrue(tokenService.resolveUserId(token).isEmpty());
    }
}
