package io.github.qwertyhgb.knowflow.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    INVALID_PARAMETER("INVALID_PARAMETER", "请求参数不合法", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "未认证或登录已过期", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "没有操作权限", HttpStatus.FORBIDDEN),
    NOT_FOUND("NOT_FOUND", "请求的资源不存在", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE("NOT_ACCEPTABLE", "无法生成客户端可接受的响应格式", HttpStatus.NOT_ACCEPTABLE),
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", "请求媒体类型不支持", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;

    private final String message;

    private final HttpStatus httpStatus;

    /**
     * enum 构造器在语法层面被强制为 private：即便不写修饰符，编译器也按 private 处理；
     * 显式写 public / protected 会直接编译报错。因此外部无法 new ErrorCode(...)，
     * 只能使用上面声明的枚举常量。
     */
    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
