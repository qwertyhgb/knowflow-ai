package io.github.qwertyhgb.knowflow.audit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解：标注在需要审计的 Controller 方法上。
 *
 * <p><strong>什么是 AOP（面向切面编程）：</strong>把「横切关注点」（日志、事务、权限）
 * 从业务代码里抽出来统一处理。本注解就是切点（Pointcut）的标记：切面匹配「带此注解的方法」，
 * 在方法执行的前后统一记录日志，业务方法本身不写任何日志代码——业务只关心业务。</p>
 *
 * <p><strong>为什么 @Retention 必须是 RUNTIME？</strong>
 * 切面在「运行时」通过反射读取注解来获取描述（value），因此注解必须保留到运行期。
 * CLASS 保留（写入字节码但不进运行时）或 SOURCE 保留（编译即丢弃）都读不到。</p>
 *
 * <p><strong>为什么只放在 Controller 层，而不放在 Service 层？</strong>
 * 操作日志记录的是「用户做了什么」（一次 HTTP 请求是一个操作单元），Controller 是用户请求的
 * 入口，一个请求只经过一次 Controller 方法——若标在 Service 上，一个请求可能触发多次
 * Service 调用（甚至内部递归/循环），会记录多次，产生重复噪音。Controller 层天然是
 * 「一个操作 = 一个方法」的粒度。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /**
     * 人类可读的操作描述，如「用户登录」「创建知识库」。
     *
     * <p>会写入 audit_log.description；机器可读的动作码（action）由切面从方法名生成。</p>
     */
    String value();
}