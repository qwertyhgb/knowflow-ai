package io.github.qwertyhgb.knowflow.common.exception;

import io.github.qwertyhgb.knowflow.common.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;

/**
 * 全局异常处理
 * <p>
 * 将应用与 Spring MVC 的常见异常转换为统一的 {@link Result} 响应：
 * <ul>
 *   <li>{@link BusinessException} —— 业务异常，按其 {@link ErrorCode#getHttpStatus()} 设置响应状态码，
 *       响应体使用 {@code Result.failure(errorCode)}，把结构化的 code / message 透传给前端。</li>
 *   <li>{@link MethodArgumentNotValidException} —— 请求体参数校验失败，固定 400，使用
 *       {@link ErrorCode#INVALID_PARAMETER}，取第一个字段的校验错误信息作为 message。</li>
 *   <li>{@link HandlerMethodValidationException} —— 方法级参数校验失败（如 {@code @RequestParam} 上的约束），
 *       固定 400，使用 {@link ErrorCode#INVALID_PARAMETER}，取第一个校验错误信息作为 message。</li>
 *   <li>{@link MethodArgumentTypeMismatchException} —— 参数类型转换失败（如 age=abc），固定 400，
 *       使用 {@link ErrorCode#INVALID_PARAMETER}，返回通用文案。</li>
 *   <li>{@link HttpMessageNotReadableException} —— 请求体无法读取（JSON 格式错误 / 请求体为空 /
 *       无法反序列化），固定 400，使用 {@link ErrorCode#INVALID_PARAMETER}，返回通用文案。</li>
 *   <li>{@link MissingServletRequestParameterException} —— 必需的请求参数缺失，固定 400，
 *       使用 {@link ErrorCode#INVALID_PARAMETER}，message 附带缺失的参数名。</li>
 *   <li>Spring MVC 的常见 4xx 异常 —— 保留正确 HTTP 状态，避免被兜底处理误报成 500。</li>
 *   <li>{@link Exception} —— 兜底处理所有未捕获异常，固定 500，记录完整堆栈，对外只返回通用文案，
 *       不向客户端暴露未知异常的真实 message（可能含敏感信息或实现细节）。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：结构化错误信息透传给前端。
     * <p>
     * message 取 {@code ex.getMessage()}：未覆盖时即 ErrorCode 的默认文案，
     * 业务方自定义后则透传自定义文案。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("Business exception: code={}, message={}", errorCode.getCode(), ex.getMessage());
        return failure(errorCode, ex.getMessage());
    }

    /**
     * 兜底异常：未知错误统一返回 500 + 通用文案，记录完整堆栈用于排查。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("Unexpected exception", ex);
        return failure(ErrorCode.INTERNAL_ERROR);
    }

    /**
     * 请求体参数校验失败：固定 400，取第一个字段错误作为 message 返回。
     * <p>
     * 不拼装所有字段错误，前端应根据 code 逐一处理；第一版先做最小可用的信息透传。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .filter(Objects::nonNull)
                .orElse("请求参数校验失败");
        log.warn("Validation failed: {}", message);
        return failure(ErrorCode.INVALID_PARAMETER, message);
    }

    /**
     * 方法级参数校验失败（如 {@code @RequestParam} / {@code @PathVariable} 上的约束）：
     * 固定 400，取第一个校验错误信息作为 message 返回。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .findFirst()
                .map(resolvableError -> resolvableError.getDefaultMessage())
                .filter(Objects::nonNull)
                .orElse("请求参数校验失败");
        log.warn("Method validation failed: {}", message);
        return failure(ErrorCode.INVALID_PARAMETER, message);
    }

    /**
     * 参数类型转换失败（如 {@code age=abc} 无法转成 Integer）：固定 400，返回通用文案。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Parameter type mismatch: parameter={}, requiredType={}",
                ex.getName(), ex.getRequiredType() == null ? "unknown" : ex.getRequiredType().getSimpleName());
        return failure(ErrorCode.INVALID_PARAMETER, "请求参数类型错误");
    }

    /**
     * 请求体无法读取（JSON 格式错误 / 请求体为空 / 无法反序列化）：固定 400，返回通用文案。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Request body is missing or malformed");
        return failure(ErrorCode.INVALID_PARAMETER, "请求体格式错误或缺失");
    }

    /**
     * 必需的请求参数缺失：固定 400，message 附带缺失的参数名。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        String message = "缺少请求参数：" + ex.getParameterName();
        log.warn("Missing request parameter: {}", ex.getParameterName());
        return failure(ErrorCode.INVALID_PARAMETER, message);
    }

    /**
     * 请求头、Cookie 等请求值缺失或不符合绑定条件。
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<Result<Void>> handleServletRequestBinding(ServletRequestBindingException ex) {
        log.warn("Servlet request binding failed: exceptionType={}", ex.getClass().getSimpleName());
        return failure(ErrorCode.INVALID_PARAMETER, "请求参数缺失或无效");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("Resource not found: {} {}", ex.getHttpMethod(), ex.getResourcePath());
        return failure(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Request method not supported: method={}", ex.getMethod());
        return failure(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.warn("No acceptable response media type");
        // 客户端明确拒绝 JSON 时，继续写统一 JSON 错误体会再次触发 406，因此只返回状态码。
        return ResponseEntity.status(ErrorCode.NOT_ACCEPTABLE.getHttpStatus()).build();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Request media type not supported: contentType={}", ex.getContentType());
        return failure(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    private ResponseEntity<Result<Void>> failure(ErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(Result.failure(errorCode));
    }

    private ResponseEntity<Result<Void>> failure(ErrorCode errorCode, String message) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(Result.failure(errorCode, message));
    }
}
