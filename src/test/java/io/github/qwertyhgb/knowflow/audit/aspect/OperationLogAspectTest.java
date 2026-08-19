package io.github.qwertyhgb.knowflow.audit.aspect;

import io.github.qwertyhgb.knowflow.audit.annotation.OperationLog;
import io.github.qwertyhgb.knowflow.audit.entity.AuditLog;
import io.github.qwertyhgb.knowflow.audit.mapper.AuditLogMapper;
import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.user.dto.request.UserLoginRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OperationLogAspect} 单元测试：直接构造切面 + mock 连接点，验证审计写入逻辑。
 *
 * <p>【为什么用 mock JoinPoint 而不用 Spring 上下文？】切面的核心逻辑（取上下文、
 * 脱敏、成功/失败判定、落库）不依赖 Spring 容器，用 Mockito 精确控制
 * {@code proceed()} 的成功/抛异常、请求上下文有无，能快速覆盖全部分支。
 * 真实「注解生效并被拦截」的端到端验证由 {@code @SpringBootTest} Web 测试承担。</p>
 */
@ExtendWith(MockitoExtension.class)
class OperationLogAspectTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-08-18T08:00:00Z");

    @Mock
    private AuditLogMapper auditLogMapper;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @Mock
    private HttpServletRequest request;

    private OperationLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new OperationLogAspect(auditLogMapper, Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
        // 默认：方法名为 login，声明类为 UserController（对应真实的 @OperationLog("用户登录")）
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn(Object.class);
        when(signature.getName()).thenReturn("login");
    }

    @AfterEach
    void cleanContext() {
        // 清理线程绑定上下文，避免影响其他测试
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    /** 构造一个带 password 的登录请求（验证脱敏用）。 */
    private UserLoginRequest loginRequest() {
        UserLoginRequest req = new UserLoginRequest();
        req.setEmail("bob@example.com");
        req.setPassword("Password123!");
        return req;
    }

    /** mock 出注解对象（运行时注解的模拟，切面只读它的 value()）。 */
    private OperationLog operationLog(String description) {
        OperationLog annotation = mock(OperationLog.class);
        when(annotation.value()).thenReturn(description);
        return annotation;
    }

    /** 给 SecurityContext 绑定一个 EnterpriseUser 主体。 */
    private void loginAs(Long userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(EnterpriseUser.withoutEnterprise(userId));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 给 RequestContext 绑定一个带 remoteAddr 的请求。 */
    private void withIp(String ip) {
        when(request.getRemoteAddr()).thenReturn(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void shouldRecordSuccessWithUserIdWhenAuthenticated() throws Throwable {
        // 已登录用户 + 请求有 IP；原方法正常返回
        loginAs(1L);
        withIp("127.0.0.1");
        when(joinPoint.getArgs()).thenReturn(new Object[]{loginRequest()});
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.logAround(joinPoint, operationLog("用户登录"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog log = captor.getValue();

        // 核心字段
        assertEquals(1L, log.getUserId(), "已认证请求应记录操作人");
        assertEquals("LOGIN", log.getAction(), "action 由方法名生成（snake_case 大写）");
        assertEquals("用户登录", log.getDescription(), "description 取注解 value");
        assertTrue(log.getSuccess(), "成功操作 success=true");
        assertNull(log.getErrorMessage(), "成功不应有错误信息");
        assertTrue(log.getDurationMs() >= 0, "耗时应是非负毫秒");
        assertEquals("127.0.0.1", log.getIp(), "应记录客户端 IP");
        assertEquals(FIXED_TIME, log.getCreatedAt(), "createdAt 用注入的 Clock");
        assertTrue(log.getMethod().contains("#login"), "method 应含方法签名");
    }

    @Test
    void shouldMaskPasswordInParams() throws Throwable {
        // 关键审计要求：密码不限明文落库
        when(joinPoint.getArgs()).thenReturn(new Object[]{loginRequest()});
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.logAround(joinPoint, operationLog("用户登录"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        String params = captor.getValue().getParams();

        assertTrue(params.contains("bob@example.com"), "非敏感字段应保留");
        assertTrue(params.contains("password=***"), "密码字段应被替换为 ***");
        assertFalse(params.contains("Password123!"), "参数中不得出现明文密码");
    }

    @Test
    void shouldSkipAuthenticationArgumentWhenMasking() throws Throwable {
        // Controller 方法常注入 Authentication 认证对象作为参数；它内含 principal/credentials
        // 等运行时状态，不是用户提交的业务参数——应被跳过，只记录真正的业务请求体
        Authentication auth = mock(Authentication.class);
        UserLoginRequest request = loginRequest();
        when(joinPoint.getArgs()).thenReturn(new Object[]{auth, request});
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.logAround(joinPoint, operationLog("用户登录"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        String params = captor.getValue().getParams();

        assertTrue(params.contains("UserLoginRequest"), "应记录业务请求参数");
        assertFalse(params.contains("UsernamePasswordAuthenticationToken"),
                "认证对象不应被序列化进参数");
        // 密码仍是脱敏占位
        assertTrue(params.contains("password=***"));
        assertFalse(params.contains("Password123!"));
    }

    @Test
    void shouldRecordFailureWithErrorTypeWhenThrows() throws Throwable {
        // 原方法抛异常：切面记失败、并透传异常（不改变业务语义）
        when(joinPoint.getArgs()).thenReturn(new Object[]{loginRequest()});
        when(joinPoint.proceed()).thenThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // 切面应把异常继续抛给调用方
        assertThrows(BusinessException.class,
                () -> aspect.logAround(joinPoint, operationLog("用户登录")));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertFalse(log.getSuccess(), "失败操作 success=false");
        assertEquals("BusinessException", log.getErrorMessage(),
                "errorMessage 只存异常类型名，不存 message/堆栈");
    }

    @Test
    void shouldRecordNullUserIdWhenUnauthenticated() throws Throwable {
        // 未设置 SecurityContext（如登录失败），取不到操作人 → userId 为 null
        when(joinPoint.getArgs()).thenReturn(new Object[]{loginRequest()});
        when(joinPoint.proceed()).thenThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        assertThrows(BusinessException.class,
                () -> aspect.logAround(joinPoint, operationLog("用户登录")));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertNull(captor.getValue().getUserId(), "未认证操作 userId 应为 null");
    }

    @Test
    void shouldNotAffectBusinessWhenInsertFails() throws Throwable {
        // 审计是旁路：落库失败绝不能影响主业务
        when(joinPoint.getArgs()).thenReturn(new Object[]{loginRequest()});
        when(joinPoint.proceed()).thenReturn("ok");
        doThrow(new RuntimeException("db down")).when(auditLogMapper).insert(any(AuditLog.class));

        // 业务返回值原样返回，切面不抛审计异常
        Object result = aspect.logAround(joinPoint, operationLog("用户登录"));

        assertEquals("ok", result, "落库失败不应影响业务结果");
    }
}