package io.github.qwertyhgb.knowflow.common.exception;

import lombok.Getter;

import java.util.Objects;

/**
 * 业务异常基类
 * <p>
 * 携带 {@link ErrorCode}，把"业务错误码 + 默认 message + HTTP 状态"一并传递给
 * {@code GlobalExceptionHandler}，避免只抛 {@code RuntimeException("xxx")}
 * 丢失结构化错误信息。
 * <p>
 * 构造器保持 public：业务代码本就需要 {@code throw new BusinessException(...)}，
 * 与 {@code Result} 那种"鼓励走静态工厂、隐藏构造器"的对象不同，访问修饰符取决于
 * 对象被合理使用的方式。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        // 把 ErrorCode 的 message 透传给父类，确保 exception.getMessage() 返回业务文案，
        // 让日志 / 框架 / 调试工具走标准访问路径也能拿到正确信息
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 需要补充上下文时使用，例如 "用户不存在：id=123"
     * <p>
     * code 仍来自 ErrorCode，只有 message 被业务方覆盖
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }
}
