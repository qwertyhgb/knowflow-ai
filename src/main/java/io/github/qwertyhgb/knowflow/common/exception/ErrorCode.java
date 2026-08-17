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
    INVALID_PASSWORD("INVALID_PASSWORD", "当前密码错误", HttpStatus.BAD_REQUEST),

    /** 创建企业邀请时，被邀请邮箱与当前用户自己的邮箱相同，固定 400。 */
    SELF_INVITATION_NOT_ALLOWED("SELF_INVITATION_NOT_ALLOWED", "不能邀请自己加入企业", HttpStatus.BAD_REQUEST),

    /** 创建或接受企业邀请时，目标用户已存在该企业成员关系（含禁用状态），固定 409。 */
    INVITEE_ALREADY_MEMBER("INVITEE_ALREADY_MEMBER", "该用户已是企业成员", HttpStatus.CONFLICT),

    /** 创建企业邀请时，该邮箱在同一企业已存在未过期的待接受邀请，固定 409。 */
    INVITATION_ALREADY_PENDING("INVITATION_ALREADY_PENDING", "该邮箱已存在待接受的邀请", HttpStatus.CONFLICT),

    /** 接受邀请时令牌不存在或无法定位邀请，固定 404。 */
    INVITATION_NOT_FOUND("INVITATION_NOT_FOUND", "邀请不存在", HttpStatus.NOT_FOUND),

    /** 接受邀请时邀请已超过有效期（含被惰性置为 EXPIRED 的情况），固定 400。 */
    INVITATION_EXPIRED("INVITATION_EXPIRED", "邀请已过期", HttpStatus.BAD_REQUEST),

    /** 接受邀请时该邀请已被接受，固定 409。 */
    INVITATION_ALREADY_ACCEPTED("INVITATION_ALREADY_ACCEPTED", "邀请已被接受", HttpStatus.CONFLICT),

    /** 接受邀请时该邀请已被邀请人撤销，固定 409。 */
    INVITATION_REVOKED("INVITATION_REVOKED", "邀请已被撤销", HttpStatus.CONFLICT),

    /** 接受邀请时当前登录邮箱与被邀请邮箱不一致，固定 403。 */
    INVITATION_EMAIL_MISMATCH("INVITATION_EMAIL_MISMATCH", "当前登录邮箱与被邀请邮箱不一致", HttpStatus.FORBIDDEN),

    /** 企业管理员试图移除自己，固定 403。 */
    SELF_REMOVE_NOT_ALLOWED("SELF_REMOVE_NOT_ALLOWED", "您不能从企业中移除自己", HttpStatus.FORBIDDEN),

    /** 被移除或修改状态的成员是企业所有者（OWNER），固定 400。 */
    CANNOT_REMOVE_OWNER("CANNOT_REMOVE_OWNER", "企业所有者不能被移除或禁用", HttpStatus.BAD_REQUEST),

    /** 企业所有者主动退出企业，固定 403。 */
    OWNER_CANNOT_LEAVE("OWNER_CANNOT_LEAVE", "企业所有者不能主动退出，当前阶段请先解散企业或移交所有权", HttpStatus.FORBIDDEN),

    /** 访问企业作用域接口时未携带或无法解析 X-Enterprise-Id 请求头，固定 400。 */
    ENTERPRISE_CONTEXT_MISSING("ENTERPRISE_CONTEXT_MISSING", "缺少企业上下文，请指定当前企业", HttpStatus.BAD_REQUEST),

    /** X-Enterprise-Id 请求头与路径中的目标企业不一致，固定 400。 */
    ENTERPRISE_CONTEXT_MISMATCH("ENTERPRISE_CONTEXT_MISMATCH", "企业上下文与目标企业不一致", HttpStatus.BAD_REQUEST),

    /** 创建或更新部门时父部门不存在或不属于当前企业，固定 404。 */
    DEPARTMENT_PARENT_NOT_FOUND("DEPARTMENT_PARENT_NOT_FOUND", "上级部门不存在", HttpStatus.NOT_FOUND),

    /** 创建或更新部门时同一父部门下已存在同名部门，固定 409。 */
    DEPARTMENT_NAME_ALREADY_EXISTS("DEPARTMENT_NAME_ALREADY_EXISTS", "同级部门名称已存在", HttpStatus.CONFLICT),

    /** 更新或删除时目标部门不存在，或不属于当前企业，固定 404。 */
    DEPARTMENT_NOT_FOUND("DEPARTMENT_NOT_FOUND", "部门不存在", HttpStatus.NOT_FOUND),

    /** 更新父部门时会形成自身引用或祖先循环，固定 400。 */
    DEPARTMENT_PARENT_CYCLE("DEPARTMENT_PARENT_CYCLE", "上级部门不能是当前部门或其子部门", HttpStatus.BAD_REQUEST),

    /** 删除部门时仍存在子部门，固定 409。 */
    DEPARTMENT_HAS_CHILDREN("DEPARTMENT_HAS_CHILDREN", "请先移动或删除子部门", HttpStatus.CONFLICT),

    /** 知识库不存在、不属于当前企业，或已禁用（禁用与不存在同样处理，不泄露状态），固定 404。 */
    KNOWLEDGE_BASE_NOT_FOUND("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在", HttpStatus.NOT_FOUND),

    /** 添加知识库成员时，该用户在该知识库已存在成员记录（唯一键兜底冲突语义），固定 409。 */
    KNOWLEDGE_BASE_MEMBER_ALREADY_EXISTS("KNOWLEDGE_BASE_MEMBER_ALREADY_EXISTS", "该用户已是知识库成员", HttpStatus.CONFLICT),

    /** 修改/移除知识库成员时，目标成员记录不存在，固定 404。 */
    KNOWLEDGE_BASE_MEMBER_NOT_FOUND("KNOWLEDGE_BASE_MEMBER_NOT_FOUND", "知识库成员不存在", HttpStatus.NOT_FOUND),

    /** 管理员试图修改或移除自己的知识库成员身份，固定 403（防止降级/退出后失去管理权）。 */
    KNOWLEDGE_BASE_SELF_OPERATION_NOT_ALLOWED("KNOWLEDGE_BASE_SELF_OPERATION_NOT_ALLOWED",
            "不能修改或移除自己的知识库成员身份", HttpStatus.FORBIDDEN);

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
