package io.github.qwertyhgb.knowflow.ai.rate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitService} 单元测试：mock StringRedisTemplate，验证固定窗口计数逻辑。
 *
 * <p>测试核心：
 * 1. 首次调用——INCR 返回 1，表示这是窗口内第一个请求，必须设置 60 秒 TTL（窗口实现）；
 * 2. 窗口内未超限——INCR 返回 15（已累计 15 次），放行且不再重复设置 TTL；
 * 3. 超限——INCR 返回 21（> 20 上限），拒绝；
 * 4. Redis 异常——INCR 抛异常时放行（降级哲学：限流是保护手段，Redis 故障不误伤用户）。</p>
 *
 * <p><strong>为什么 mock 而非真实 Redis？</strong>限流核心是计数与比较的「逻辑」，
 * 单元测试只需验证 INCR/EXPIRE 是否以正确键与参数被调用、返回值如何处理；
 * 真实 Redis 的连键/过期行为由 {@code RedisTokenIntegrationTest} 这类集成测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        // StringRedisTemplate.opsForValue() 返回 ValueOperations（INCR 命令的入口），
        // 用 mock 的 ValueOperations 桩住增量返回，隔离真实 Redis。
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        rateLimitService = new RateLimitService(redisTemplate);
    }

    @Test
    void shouldAcquireOnFirstRequestAndSetExpire() {
        // 场景：窗口内第一次调用 → INCR 返回 1（键不存在时 INCR 先建键再自增）
        String action = "ai_chat";
        Long userId = 1L;
        when(valueOperations.increment("knowflow:ratelimit:" + action + ":" + userId)).thenReturn(1L);

        boolean acquired = rateLimitService.tryAcquire(userId, action);

        // 首次必然放行（1 <= 20）
        assertTrue(acquired, "首次调用应放行");

        // 关键断言：首次自增（返回 1）时必须设置 60 秒过期——这是「窗口」的实现，TTL 即窗口长度。
        // 【为什么断言的是正确窗口时长？】窗口结束键自动过期归零 = 计数自动重置；
        // 若忘记设 TTL，计数将永久累积，用户会被永久限流。60 秒必须与 WINDOW_SECONDS 一致。
        // 注意：captor 是匹配器，另一个参数必须也用匹配器（eq），不能混用裸值。
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisTemplate).expire(eq("knowflow:ratelimit:" + action + ":" + userId), durationCaptor.capture());
        assertEquals(Duration.ofSeconds(60), durationCaptor.getValue(), "首次进窗应设置 60 秒 TTL");
    }

    @Test
    void shouldAcquireWhenWithinWindow() {
        // 场景：窗口内已累计 15 次，第 16 次仍放行（15 <= 20）
        when(valueOperations.increment("knowflow:ratelimit:ai_chat:1")).thenReturn(15L);

        boolean acquired = rateLimitService.tryAcquire(1L, "ai_chat");

        assertTrue(acquired, "窗口内未超限应放行");
        // INCR 返回非 1 时不应重复设置 TTL（窗口生命周期只有一次定长，重复设无意义且多余）
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void shouldRejectWhenOverLimit() {
        // 场景：窗口内已累计 20 次，第 21 次 INCR 返回 21 → 超过上限 20 → 拒绝
        when(valueOperations.increment("knowflow:ratelimit:ai_chat:1")).thenReturn(21L);

        boolean acquired = rateLimitService.tryAcquire(1L, "ai_chat");

        assertFalse(acquired, "超过限额应拒绝");
    }

    @Test
    void shouldPassThroughWhenRedisFails() {
        // 场景：Redis 故障（INCR 抛异常）→ 放行。
        // 【为什么故障要放行？】限流是「保护手段」不是「正确性依赖」——Redis 挂了宁可
        // 多花几分钱放行，也不让所有 AI 对话接口对正常用户报错（接口 500 的后果远比
        // 一次多余的大模型调用严重）。与知识库缓存、向量化锁的降级是同一哲学。
        when(valueOperations.increment("knowflow:ratelimit:ai_chat:1"))
                .thenThrow(new RuntimeException("connection refused"));

        boolean acquired = rateLimitService.tryAcquire(1L, "ai_chat");

        assertTrue(acquired, "Redis 异常时应降级放行");
    }

    @Test
    void shouldUseSeparateCountersPerAction() {
        // 场景：不同 action（ai_chat / ai_rag）使用不同键——用户聊普通对话不消耗 RAG 配额。
        // 【为什么必须分开？】AI 对话与 RAG 是两种不同的付费接口形态，若共用计数器，
        // 用户聊 20 句普通对话就会被 RAG 拒之门外，体验上不合理；独立键让每种动作
        // 有独立的「每分钟 N 次」预算。
        when(valueOperations.increment("knowflow:ratelimit:ai_chat:1")).thenReturn(1L);
        when(valueOperations.increment("knowflow:ratelimit:ai_rag:1")).thenReturn(1L);

        rateLimitService.tryAcquire(1L, "ai_chat");
        rateLimitService.tryAcquire(1L, "ai_rag");

        // 两个动作各 INCR 了一次各自的键，互不影响
        verify(valueOperations).increment("knowflow:ratelimit:ai_chat:1");
        verify(valueOperations).increment("knowflow:ratelimit:ai_rag:1");
    }
}