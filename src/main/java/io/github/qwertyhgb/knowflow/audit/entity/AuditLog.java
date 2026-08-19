package io.github.qwertyhgb.knowflow.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 操作日志实体，对应表 {@code audit_log}。
 *
 * <p>每一条记录对应一次被 {@code @OperationLog} 标注的业务方法调用：谁（操作人）、
 * 做了什么（action + description + 脱敏参数）、成没成（success + errorMessage）、
 * 花了多久（durationMs）、从哪来（ip）、何时（createdAt）。</p>
 *
 * <p>实体保持与表列一一对应（userId / action / description / method / success /
 * errorMessage / durationMs / ip / params / createdAt）。字段使用 {@link Instant}，
 * 时间由统一 API 序列化层输出为 ISO-8601 UTC 字符串。</p>
 */
@Getter
@Setter
@TableName("audit_log")
public class AuditLog {

    /** 主键：数据库自增。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作人用户 ID；未认证操作（如登录失败）为 null。 */
    private Long userId;

    /** 动作标识（机器可读），由切面从方法名生成，如 USER_LOGIN。 */
    private String action;

    /** 人类可读描述，取注解 value，如「用户登录」。 */
    private String description;

    /** 触发方法签名，如 io...UserController#login。 */
    private String method;

    /** 是否成功：true=成功 / false=失败。 */
    private Boolean success;

    /** 失败原因（仅异常类型名，不存堆栈），成功时为 null。 */
    private String errorMessage;

    /** 耗时毫秒。 */
    private Long durationMs;

    /** 客户端 IP；取不到时为 null。 */
    private String ip;

    /** 脱敏后的请求参数（敏感字段已替换为 ***），不记录时可为 null。 */
    private String params;

    /** 创建时间（UTC）。 */
    private Instant createdAt;
}