package io.github.qwertyhgb.knowflow.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 统一错误码枚举，是全局异常处理与业务异常的结构化错误载体。
 *
 * <p>每个枚举常量由三部分组成：</p>
 * <ul>
 *   <li><strong>{@link #code}</strong> —— 机器可读的稳定错误码（大写 {@code SNAKE_CASE}），
 *       供前端按 code 做分支处理，不随文案变更而失效。</li>
 *   <li><strong>{@link #message}</strong> —— 默认的人类可读文案，业务侧可在抛出
 *       {@link BusinessException} 时覆盖（如补充具体上下文）。</li>
 *   <li><strong>{@link #httpStatus}</strong> —— 对应的 HTTP 状态码，由
 *       {@code GlobalExceptionHandler} 读取后设置到响应状态。</li>
 * </ul>
 *
 * <p><strong>分组约定：</strong></p>
 * <ul>
 *   <li><strong>通用错误码</strong>（上半段）：与具体业务无关的框架级错误，
 *       如参数校验失败、未认证、资源不存在等。</li>
 *   <li><strong>业务错误码</strong>（下半段，以 {@code // 业务错误码} 注释分隔）：
 *       由具体业务模块按需新增，命名沿用「场景 + 动作」的语义化风格，
 *       例如 {@link #EMAIL_ALREADY_EXISTS}、{@link #INVALID_CREDENTIALS}。</li>
 * </ul>
 *
 * <p>新增错误码时必须保持 {@code code} 稳定（上线后不得改名），
 * 因为前端逻辑与历史日志都依赖它。</p>
 */
@Getter
public enum ErrorCode {

    /** 请求参数不合法（校验失败、格式错误、类型不匹配等），固定 400。 */
    INVALID_PARAMETER("INVALID_PARAMETER", "请求参数不合法", HttpStatus.BAD_REQUEST),

    /** 未认证或登录态已失效（如未携带 Token、Token 无效或过期），固定 401。 */
    UNAUTHORIZED("UNAUTHORIZED", "未认证或登录已过期", HttpStatus.UNAUTHORIZED),

    /** 已认证但无操作权限，固定 403。 */
    FORBIDDEN("FORBIDDEN", "没有操作权限", HttpStatus.FORBIDDEN),

    /** 请求的资源不存在（如查询的用户 ID 不存在），固定 404。 */
    NOT_FOUND("NOT_FOUND", "请求的资源不存在", HttpStatus.NOT_FOUND),

    /** 请求方法不支持（如对只读接口发起 POST），固定 405。 */
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),

    /** 请求媒体类型不支持（Content-Type 不在接口接受范围内），固定 415。 */
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", "请求媒体类型不支持", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    /** 服务器内部错误（兜底，不向客户端暴露真实异常信息），固定 500。 */
    INTERNAL_ERROR("INTERNAL_ERROR", "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR),

    // -------------------- 业务错误码 --------------------

    /** 注册时邮箱已被占用，固定 409（与唯一索引兜底冲突语义一致）。 */
    EMAIL_ALREADY_EXISTS("EMAIL_ALREADY_EXISTS", "邮箱已被注册", HttpStatus.CONFLICT),

    /** 登录时邮箱不存在或密码错误，统一返回同一错误码以防范账号枚举攻击，固定 401。 */
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "邮箱或密码错误", HttpStatus.UNAUTHORIZED),

    /** 登录时账号被禁用，凭证校验通过后才返回，固定 403。 */
    USER_DISABLED("USER_DISABLED", "账号已被禁用", HttpStatus.FORBIDDEN),

    /** 修改密码时当前密码错误。此场景下用户已登录，无需防范账号枚举，固定 400。 */
    INVALID_PASSWORD("INVALID_PASSWORD", "当前密码错误", HttpStatus.BAD_REQUEST);

    /** 稳定、机器可读的错误码，前端分支与日志检索的依据。 */
    private final String code;

    /** 默认业务文案，可被 {@link BusinessException} 覆盖。 */
    private final String message;

    /** 对应 HTTP 状态码，由全局异常处理器映射到响应状态。 */
    private final HttpStatus httpStatus;

    /**
     * enum 构造器在语法层面被强制为 private：即便不写修饰符，编译器也按 private 处理；
     * 显式写 public / protected 会直接编译报错。因此外部无法 new ErrorCode(...)，
     * 只能使用上面声明的枚举常量。
     *
     * @param code       稳定错误码
     * @param message    默认业务文案
     * @param httpStatus 对应 HTTP 状态码
     */
    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
