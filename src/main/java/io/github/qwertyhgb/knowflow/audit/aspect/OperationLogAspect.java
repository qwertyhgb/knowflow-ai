package io.github.qwertyhgb.knowflow.audit.aspect;

import io.github.qwertyhgb.knowflow.audit.annotation.OperationLog;
import io.github.qwertyhgb.knowflow.audit.entity.AuditLog;
import io.github.qwertyhgb.knowflow.audit.mapper.AuditLogMapper;
import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 操作日志切面：拦截所有标注 {@code @OperationLog} 的 Controller 方法，统一记录操作日志。
 *
 * <p><strong>AOP 的完整心智模型（本类承载）：</strong></p>
 * <ul>
 *   <li><strong>切点（Pointcut）——匹配哪些方法：</strong>这里用 {@code @annotation(operationLog)}
 *       匹配「带有 @OperationLog 注解的方法」。注解即切点的标记，业务方法只要加注解就被拦截。</li>
 *   <li><strong>通知（Advice）——做什么：</strong>{@link #logAround} 是环绕通知 {@code @Around}，
 *       它包裹目标方法，可以精确控制「方法执行前 / 执行后 / 抛异常时」三段时机。</li>
 *   <li><strong>织入（Weaving）——什么时候生效：</strong>Spring AOP 在应用启动时对匹配的 bean
 *       生成代理（默认：目标实现接口用 JDK 动态代理，没有接口用 CGLIB 子类代理），
 *       调用代理方法即触发通知。</li>
 * </ul>
 *
 * <p><strong>为什么用 {@code @Around} 而不是 {@code @Before} + {@code @AfterReturning}
 * + {@code @AfterThrowing} 拆三个通知？</strong>
 * 环绕通知一次拿到完整上下文（执行前信息 + 执行结果/异常 + 全程耗时），且 Cron 里
 * {@code proceed()} 的返回值/异常能原位流转，逻辑内聚、少拆方法。要记录「成功/失败 +
 * 耗时 + 异常」都需要「方法前后」两段信息，用一个 {@code @Around} 最自然。</p>
 *
 * <p><strong>什么是横切关注点：</strong>日志、事务、权限这类「每个方法都可能需要、
 * 但和具体业务无关」的公共关注点。把它们抽到切面统一处理，业务方法保持干净，
 * 新增审计能力只改切面、不动业务（开闭原则）。</p>
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    /** 需要脱敏的字段名关键词（不区分大小写）：密码、令牌等。 */
    private static final Set<String> SENSITIVE_FIELDS =
            Set.of("password", "newpassword", "token", "authorization");

    /** 脱敏后的占位值。 */
    private static final String MASK = "***";

    /** 参数 JSON 的最大长度，超长截断（防止超长参数撑爆表）。 */
    private static final int PARAMS_MAX_LENGTH = 500;

    private final AuditLogMapper auditLogMapper;
    private final Clock clock;

    public OperationLogAspect(AuditLogMapper auditLogMapper, Clock clock) {
        this.auditLogMapper = auditLogMapper;
        this.clock = clock;
    }

    /**
     * 环绕通知：记录一次被注解方法的完整调用信息。
     *
     * <p>流程：前置取上下文（操作人/IP/脱敏参数）→ 执行原方法 → 根据结果/异常标定
     * 成功/失败 → 计算耗时 → 组日志对象 → <strong>落库失败被吞掉，绝不影响主流程</strong>。</p>
     *
     * @param pjp          连接点：proceed() 调用原方法，getSignature/getArgs 提供方法元信息
     * @param operationLog 匹配到的注解对象（由 Spring AOP 注入）
     * @return 原方法返回结果（异常则透传，主流程语义不变）
     */
    @Around("@annotation(operationLog)")
    public Object logAround(ProceedingJoinPoint pjp, OperationLog operationLog) throws Throwable {
        // ---- 前置：收集请求上下文 ----
        Long userId = currentUserId();
        String ip = clientIp();
        String params = maskParams(pjp.getArgs());

        long startNanos = System.nanoTime();
        boolean success = true;
        String errorMessage = null;

        try {
            // 执行原方法。注意：返回值必须原样返回，切面不能吞掉业务结果
            return pjp.proceed();
        } catch (Throwable e) {
            // 原方法抛异常：标记失败，但异常必须继续向上抛（切面不改变业务异常语义）
            success = false;
            // 只存异常类名，不存 message：message 可能含用户输入（敏感），详情交给应用日志
            errorMessage = e.getClass().getSimpleName();
            throw e;
        } finally {
            // 无论成功失败都执行：计算耗时并落库
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

            AuditLog auditLog = new AuditLog();
            auditLog.setUserId(userId);
            auditLog.setAction(toAction(pjp));
            auditLog.setDescription(operationLog.value());
            auditLog.setMethod(methodSignature(pjp));
            auditLog.setSuccess(success);
            auditLog.setErrorMessage(errorMessage);
            auditLog.setDurationMs(durationMs);
            auditLog.setIp(ip);
            auditLog.setParams(params);
            auditLog.setCreatedAt(clock.instant());

            // 【为什么落库异常要 try-catch 吞掉？】审计是旁路（横切关注点）：
            // 记录失败绝不能让主业务跟着失败。这与限流/缓存降级是同一哲学——
            // 旁路能力不可用时应降级（不记录），而不是把主流程拖垮。
            try {
                auditLogMapper.insert(auditLog);
            } catch (RuntimeException e) {
                log.warn("event=audit_log_failed method={} success={}",
                        auditLog.getMethod(), success);
            }
        }
    }

    // ==================== 上下文解析 ====================

    /**
     * 取当前登录用户 ID。
     *
     * <p>从 {@link SecurityContextHolder} 取认证信息：认证主体是 {@link EnterpriseUser}
     * （Token 认证建立）时取 userId；未认证请求（如登录失败）取不到认证或主体不是
     * EnterpriseUser，返回 null 表示「未登录操作」。</p>
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof EnterpriseUser enterpriseUser) {
            return enterpriseUser.userId();
        }
        return null;
    }

    /**
     * 取客户端 IP。
     *
     * <p>从线程绑定的请求上下文取当前 {@code HttpServletRequest} 的 {@code getRemoteAddr()}。
     * Web 层调用才有请求上下文；非 Web 调用（如单元测试直调）取不到，返回 null。</p>
     */
    private String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    // ==================== 行为生成与参数脱敏 ====================

    /**
     * 生成方法签名描述：{声明类名}#{方法名}，如 io...UserController#login。
     */
    private String methodSignature(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        return signature.getDeclaringType().getName() + "#" + signature.getName();
    }

    /**
     * 从方法名生成动作标识（机器可读、可检索、可统计）。
     *
     * <p>camelCase 方法名转 SNAKE_CASE 大写：login → LOGIN、
     * createKnowledgeBase → CREATE_KNOWLEDGE_BASE。这样数据库里 action 列
     * 可以稳定地按动作统计（某动作发生了多少次）、按动作过滤。</p>
     */
    private String toAction(ProceedingJoinPoint pjp) {
        String methodName = pjp.getSignature().getName();
        return methodName
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT);
    }

    /**
     * 把方法参数生成「脱敏 + 截断」的日志字符串。
     *
     * <p>逐个参数：基础类型/String/枚举直接输出；DTO 对象则反射遍历其字段，
     * <strong>字段名匹配敏感关键词（password/newPassword/token/authorization，忽略大小写）
     * 的用 {@code ***} 替换</strong>——这就是日志白名单在审计场景的落地：
     * 绝不把密码/令牌明文落库。最终字符串统一截断 500 字符，防止超长参数撑爆
     * varchar 列（也防止把大对象全文写进审计表）。</p>
     */
    private String maskParams(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        List<String> parts = new ArrayList<>(args.length);
        for (Object arg : args) {
            // 跳过 Spring Security 的认证对象（Authentication）：它是框架在方法执行前
            // 注入的「运行时主体」，不是用户提交的业务参数。反射读取它既无用，
            // 又有泄露内部状态（如 principal、甚至 credentials）的风险。
            if (arg instanceof Authentication) {
                continue;
            }
            parts.add(maskValue(arg));
        }
        if (parts.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", parts);
        return joined.length() <= PARAMS_MAX_LENGTH
                ? joined
                : joined.substring(0, PARAMS_MAX_LENGTH);
    }

    /** 把单个参数值转成脱敏后的表示。 */
    private String maskValue(Object arg) {
        if (arg == null) {
            return "null";
        }
        // 基础类型 / String / 包装类型 / 枚举：直接可读，无内部敏感字段
        Class<?> type = arg.getClass();
        if (type.isPrimitive() || arg instanceof String || arg instanceof Number
                || arg instanceof Boolean || arg instanceof Character || arg instanceof Enum) {
            return String.valueOf(arg);
        }
        // DTO 对象：反射遍历字段，敏感字段脱敏
        StringBuilder sb = new StringBuilder(type.getSimpleName()).append('{');
        boolean first = true;
        for (Field field : declaredFields(type)) {
            field.setAccessible(true);
            try {
                Object value = field.get(arg);
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(field.getName()).append('=')
                        .append(isSensitiveField(field.getName()) ? MASK : String.valueOf(value));
            } catch (IllegalAccessException e) {
                // 理论上 setAccessible 后不会发生；防御性跳过该字段
            }
        }
        return sb.append('}').toString();
    }

    /** 收集类及其父类的非静态字段（含基类字段）。 */
    private List<Field> declaredFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    /** 字段名是否命中敏感关键词（忽略大小写）。 */
    private boolean isSensitiveField(String fieldName) {
        return SENSITIVE_FIELDS.contains(fieldName.toLowerCase(Locale.ROOT));
    }
}