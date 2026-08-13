package io.github.qwertyhgb.knowflow.common.response;

import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import lombok.Getter;

import java.util.Objects;

@Getter
public class Result<T> {

    private static final String SUCCESS_CODE = "SUCCESS";

    private static final String SUCCESS_MESSAGE = "success";

    private final String code;

    private final String message;

    private final T data;

    private Result(String code, String message, T data) {
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.data = data;
    }

    /**
     * 成功且有数据
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    /**
     * 成功但没有数据
     */
    public static <T> Result<T> success() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    /**
     * 失败，code 和 message 从 ErrorCode 中取
     */
    public static <T> Result<T> failure(ErrorCode errorCode) {
        ErrorCode requiredErrorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        return new Result<>(requiredErrorCode.getCode(), requiredErrorCode.getMessage(), null);
    }

    /**
     * 失败，code 取 ErrorCode，message 由调用方覆盖（如 "用户不存在：id=123"）
     */
    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        ErrorCode requiredErrorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        return new Result<>(requiredErrorCode.getCode(), message, null);
    }
}
