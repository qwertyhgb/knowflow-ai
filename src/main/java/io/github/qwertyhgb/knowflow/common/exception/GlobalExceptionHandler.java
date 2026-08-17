package io.github.qwertyhgb.knowflow.common.exception;

import io.github.qwertyhgb.knowflow.common.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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
 *   <li>{@link HttpMessageNotReadableException} —— 请求体无法读取（JSON 格式错误 / 请求体为空 /
 *       无法反序列化），固定 400，使用 {@link ErrorCode#INVALID_PARAMETER}，返回通用文案。</li>
 *   <li>当前业务会遇到的常见 4xx 异常 —— 404、405、415 保留正确 HTTP 状态。</li>
 *   <li>{@link Exception} —— 兜底处理所有未捕获异常，固定 500，记录完整堆栈，对外只返回通用文案，
 *       不向客户端暴露未知异常的真实 message（可能含敏感信息或实现细节）。</li>
 * </ul>
 *
 * <p>学习版不提前处理尚未出现的参数类型、方法级校验或自定义 Header 异常；
 * 等真实接口用到相应能力时，再添加对应的 {@code @ExceptionHandler}。</p>
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
        log.warn("event=business_exception code={}", errorCode.getCode());
        return failure(errorCode, ex.getMessage());
    }

    /**
     * 方法级鉴权失败（@PreAuthorize 校验不通过）。
     *
     * <p>Spring Security 6 的方法级安全实际抛出 {@code AuthorizationDeniedException}，
     * 它是本类处理的 {@link AccessDeniedException} 的子类，因此处理父类即可。
     * 该异常在 Controller 方法执行前抛出，属于可预期的权限拒绝，而非未预期错误：
     * 统一转为 403 + {@link ErrorCode#FORBIDDEN}，记 WARN 且不输出堆栈
     * （与 {@link BusinessException} 的处理风格一致，不按兜底异常记 ERROR）。</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("event=access_denied");
        return failure(ErrorCode.FORBIDDEN);
    }

    /**
     * 兜底异常：未知错误统一返回 500 + 通用文案，记录完整堆栈用于排查。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("event=unexpected_exception", ex);
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
        log.warn("event=request_body_validation_failed errorCount={}", ex.getErrorCount());
        return failure(ErrorCode.INVALID_PARAMETER, message);
    }

    /**
     * 请求体无法读取（JSON 格式错误 / 请求体为空 / 无法反序列化）：固定 400，返回通用文案。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("event=request_body_not_readable");
        return failure(ErrorCode.INVALID_PARAMETER, "请求体格式错误或缺失");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("event=resource_not_found method={} path={}", ex.getHttpMethod(), ex.getResourcePath());
        return failure(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("event=request_method_not_supported method={}", ex.getMethod());
        return failure(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("event=request_media_type_not_supported contentType={}", ex.getContentType());
        return failure(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * 请求参数缺失（如未传必需参数）：Spring MVC 在参数解析阶段触发此异常，早于
     * Controller 方法体。固定 400 + INVALID_PARAMETER，记 WARN 不记堆栈。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex) {
        log.warn("event=request_param_missing name={} paramType={}", ex.getParameterName(), ex.getParameterType());
        return failure(ErrorCode.INVALID_PARAMETER, "请求参数 " + ex.getParameterName() + " 不能为空");
    }

    /**
     * multipart 文件大小超限：Spring Boot 在 multipart 解析阶段触发此异常，早于
     * Controller 参数校验。固定 400 + DOCUMENT_TOO_LARGE，记 WARN 不记堆栈
     * （参照 AccessDeniedException 处理器风格：可预期的异常输入，不按兜底异常 ERROR 处理）。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("event=upload_size_exceeded maxSize={}", ex.getMaxUploadSize());
        return failure(ErrorCode.DOCUMENT_TOO_LARGE);
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
