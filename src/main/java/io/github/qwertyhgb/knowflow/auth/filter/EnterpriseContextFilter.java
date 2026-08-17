package io.github.qwertyhgb.knowflow.auth.filter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRolePermissionMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.server.RequestPath;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * 企业上下文过滤器：为「企业作用域」请求校验并注入当前企业上下文。
 *
 * <p><strong>规则（强制模式）：</strong>匹配
 * {@code /api/enterprises/{enterpriseId}} 或
 * {@code /api/enterprises/{enterpriseId}/**} 的请求被视为企业作用域，
 * 必须携带 {@value #ENTERPRISE_ID_HEADER} 请求头：</p>
 * <ul>
 *   <li>尚未认证 → 直接放行，交由认证入口点返回 401（先认证，再谈上下文）；</li>
 *   <li>请求头缺失或不是合法数字 → 400 {@code ENTERPRISE_CONTEXT_MISSING}；</li>
 *   <li>请求头与路径中的企业 ID 不一致 → 400 {@code ENTERPRISE_CONTEXT_MISMATCH}
 *       （防止「以 A 企业上下文调用 B 企业接口」的越权尝试）；</li>
 *   <li>当前用户不是目标企业的正常成员 → 403；</li>
 *   <li>成员关联的角色不存在或被禁用 → 403（成员已无可用角色，按无权限拒绝）；</li>
 *   <li>校验通过 → 重建认证主体，填入 {@code currentEnterpriseId} 与成员角色编码
 *       （{@code enterprise_role.code}），
 *       并把该角色拥有的权限码写入 {@code Authentication.getAuthorities()}，
 *       供 {@code @PreAuthorize("hasAuthority('member:remove')")} 等声明式接口鉴权匹配。
 *       下游 Controller 可从 {@link EnterpriseUser} 直接读取企业上下文。</li>
 * </ul>
 *
 * <p>不匹配企业路径的请求（注册/登录、我的企业列表、被邀请人视角的邀请接口等）
 * 不受本规则约束，原样放行。</p>
 *
 * <p><strong>为什么在 Filter 层做成员校验：</strong>把「企业上下文必须合法」
 * 提升为全局规则后，企业作用域内的任何接口都不可能在无合法上下文时被执行；
 * Service 层既有的成员校验保留作为纵深防御，两者不冲突。</p>
 *
 * <p>错误响应发生在 Filter Chain 中，早于 {@code GlobalExceptionHandler}，
 * 因此这里直接写出与业务异常一致的 {@link Result} 结构
 * （与 {@code RestAuthenticationEntryPoint} 同一模式）。</p>
 */
@Slf4j
public class EnterpriseContextFilter extends OncePerRequestFilter {

    /** 当前企业上下文请求头：前端选定企业后随每个企业作用域请求携带。 */
    public static final String ENTERPRISE_ID_HEADER = "X-Enterprise-Id";

    /**
     * 企业作用域路径模式：{enterpriseId} 单段路径与其任意子路径。
     * {@code POST /api/enterprises}（创建企业）与 {@code GET /api/enterprises}
     * （我的企业列表）不含路径企业 ID，不匹配本模式，因此不需要上下文。
     */
    private static final List<PathPattern> ENTERPRISE_PATTERNS = List.of(
            PathPatternParser.defaultInstance.parse("/api/enterprises/{enterpriseId}"),
            PathPatternParser.defaultInstance.parse("/api/enterprises/{enterpriseId}/**"));

    private final EnterpriseMemberMapper enterpriseMemberMapper;

    private final EnterpriseRoleMapper enterpriseRoleMapper;

    private final EnterpriseRolePermissionMapper enterpriseRolePermissionMapper;

    private final JsonMapper jsonMapper;

    public EnterpriseContextFilter(EnterpriseMemberMapper enterpriseMemberMapper,
                                   EnterpriseRoleMapper enterpriseRoleMapper,
                                   EnterpriseRolePermissionMapper enterpriseRolePermissionMapper,
                                   JsonMapper jsonMapper) {
        this.enterpriseMemberMapper = enterpriseMemberMapper;
        this.enterpriseRoleMapper = enterpriseRoleMapper;
        this.enterpriseRolePermissionMapper = enterpriseRolePermissionMapper;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        // ========== 阶段 1：检查认证状态 ==========
        // 从 SecurityContext 获取当前认证主体。只有经过 TokenAuthenticationFilter 认证的请求，
        // 才会持有 EnterpriseUser 类型的 principal。
        // 以下两种情况下直接放行，不再执行企业上下文校验：
        //   a) authentication == null：未认证，交给 RestAuthenticationEntryPoint 返回 401
        //   b) principal 不是 EnterpriseUser 类型：由其他认证机制建立的主体，本项目不处理
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof EnterpriseUser currentUser)) {
            chain.doFilter(request, response);
            return;
        }

        // ========== 阶段 2：判断是否为企业作用域请求 ==========
        // 通过路径模式匹配识别：只有 /api/enterprises/{enterpriseId} 及其子路径
        // 被视为企业作用域（如 /api/enterprises/1/members）。
        // 非企业作用域请求（如 /api/enterprises 列表接口、/api/users/me 等）直接放行。
        Long pathEnterpriseId = resolvePathEnterpriseId(request);
        if (pathEnterpriseId == null) {
            chain.doFilter(request, response);
            return;
        }

        // ========== 阶段 3：请求头校验 ==========
        // 企业作用域请求必须携带 X-Enterprise-Id 请求头，指明当前要操作哪个企业。
        // 请求头缺失或值不是合法数字 → 400，明确告知调用方缺少上下文。
        Long headerEnterpriseId = parseEnterpriseId(request.getHeader(ENTERPRISE_ID_HEADER));
        if (headerEnterpriseId == null) {
            writeError(response, ErrorCode.ENTERPRISE_CONTEXT_MISSING);
            return;
        }

        // ========== 阶段 4：上下文一致性校验 ==========
        // 请求头中的企业 ID 必须与 URL 路径中的企业 ID 一致。
        // 防止攻击者以「A 企业的上下文」调用「B 企业的接口」——这是最常见的多租户越权模式。
        // 例如：X-Enterprise-Id: 1 访问 /api/enterprises/2/members 将被拒绝。
        if (!headerEnterpriseId.equals(pathEnterpriseId)) {
            writeError(response, ErrorCode.ENTERPRISE_CONTEXT_MISMATCH);
            return;
        }

        // ========== 阶段 5：成员身份校验 ==========
        // 查询 enterprise_member 表，确认当前用户是目标企业的正常成员。
        // 三个查询条件：
        //   1) enterpriseId = 目标企业（来自请求头）
        //   2) userId = 当前认证用户（来自 Token 认证阶段建立的 principal）
        //   3) status = NORMAL（已邀请但未接受、已离职、已禁用的成员均不可操作）
        EnterpriseMember member = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, headerEnterpriseId)
                        .eq(EnterpriseMember::getUserId, currentUser.userId())
                        .eq(EnterpriseMember::getStatus, EnterpriseMemberStatus.NORMAL));
        if (member == null) {
            // 日志只记录企业 ID 和用户 ID 等系统标识，不记录 PII。
            // 上下文非法属于可预期的异常输入，记 WARN 不记堆栈，避免日志噪音。
            log.warn("event=enterprise_context_rejected enterpriseId={} userId={}",
                    headerEnterpriseId, currentUser.userId());
            writeError(response, ErrorCode.FORBIDDEN);
            return;
        }

        // ========== 阶段 6：角色与权限校验 ==========
        // 6.1 成员必须关联角色：role_id 不能为空。
        // 这是一个防御性分支——V6 数据库迁移已为所有成员回填 role_id 并改为非空约束。
        // 保留此检查是为了应对数据库迁移遗漏或脚本回滚等极端情况。
        if (member.getRoleId() == null) {
            log.warn("event=member_role_missing enterpriseId={} userId={}",
                    headerEnterpriseId, currentUser.userId());
            writeError(response, ErrorCode.FORBIDDEN);
            return;
        }

        // 6.2 角色必须处于正常状态：角色被删除或禁用时，该角色的所有成员应失去权限。
        // 这里不检查角色是否存在于 enterprise_role 表中（selectById 返回 null 即不存在），
        // 也不检查角色是否属于当前企业（角色表按 enterprise 隔离，SQL 层面已保证）。
        EnterpriseRole role = enterpriseRoleMapper.selectById(member.getRoleId());
        if (role == null || role.getStatus() != EnterpriseRoleStatus.NORMAL) {
            log.warn("event=role_disabled_or_missing roleId={} enterpriseId={} userId={}",
                    member.getRoleId(), headerEnterpriseId, currentUser.userId());
            writeError(response, ErrorCode.FORBIDDEN);
            return;
        }

        // 6.3 加载角色拥有的权限码列表。
        // 权限码来自 permission 表，通过 enterprise_role_permission 关联表查询。
        // 权限码示例：enterprise:update, member:remove, invitation:create 等。
        // 加载失败（如数据库宕机）按「无权限」拒绝，日志仅记录异常类型，不包含 SQL 细节。
        List<String> permissionCodes;
        try {
            permissionCodes = enterpriseRolePermissionMapper.selectPermissionCodesByRoleId(member.getRoleId());
        } catch (RuntimeException ex) {
            log.warn("event=permission_load_failed roleId={} errorType={}",
                    member.getRoleId(), ex.getClass().getSimpleName());
            writeError(response, ErrorCode.FORBIDDEN);
            return;
        }

        // ========== 阶段 7：重建认证主体 ==========
        // 将权限码转换为 Spring Security 的 GrantedAuthority 对象。
        // 这是多租户 RBAC 的核心：权限码被注入到 Authentication.getAuthorities() 中，
        // 后续 Controller 方法上的 @PreAuthorize("hasAuthority('member:remove')") 注解
        // 直接从 Authentication.getAuthorities() 匹配权限，无需在 Controller 中手写 if-else 判断。
        // 这是一种声明式鉴权方案，比手动校验更简洁、更易维护。
        List<GrantedAuthority> authorities = permissionCodes.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        // 重建 UsernamePasswordAuthenticationToken：
        //   - principal：从 EnterpriseUser.withoutEnterprise(userId) 升级为
        //               EnterpriseUser.withEnterprise(enterpriseId, roleCode)，
        //               携带了企业上下文信息，后续 Controller 可以直接从认证主体读取。
        //   - credentials：保持 null（Token 认证，不传递密码凭证）。
        //   - authorities：替换为当前角色的权限码列表（之前为空）。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        currentUser.withEnterprise(headerEnterpriseId, role.getCode()),
                        null,
                        authorities));

        // ========== 放行 ==========
        // 此时 SecurityContext 中的 Authentication 已携带完整的企业上下文和权限信息，
        // 后续 Filter 和 Controller 可以直接使用。
        chain.doFilter(request, response);
    }

    /**
     * 从请求路径解析目标企业 ID；不匹配企业作用域模式时返回 null。
     *
     * <p>路径变量可能是非数字（如 {@code /api/enterprises/abc}），
     * 解析失败同样返回 null 放行——该请求随后会因 Controller 的
     * {@code @PathVariable Long} 类型转换失败被全局异常处理兜底，
     * 保持与引入本过滤器前一致的行为。</p>
     *
     * <p>路径匹配逻辑：</p>
     * <ul>
     *   <li>{@code /api/enterprises/1} → 匹配模式 1，enterpriseId=1</li>
     *   <li>{@code /api/enterprises/1/members} → 匹配模式 2，enterpriseId=1</li>
     *   <li>{@code /api/enterprises}（列表接口）→ 不匹配任何模式，返回 null</li>
     *   <li>{@code /api/enterprises/abc}（非法 ID）→ 匹配模式 1 或 2，但 parseEnterpriseId 返回 null</li>
     * </ul>
     */
    private Long resolvePathEnterpriseId(HttpServletRequest request) {
        // 安全过滤器链先于 DispatcherServlet 执行，此时 request 的 URI 解析缓存尚未写入。
        // hasParsedRequestPath 判断后按需调用 parseAndCache：
        //   - 若尚未解析 → 解析并缓存到 request 属性，后续 Filter/Interceptor/Controller 可复用
        //   - 若已解析 → 跳过，避免重复解析开销
        if (!ServletRequestPathUtils.hasParsedRequestPath(request)) {
            ServletRequestPathUtils.parseAndCache(request);
        }
        RequestPath requestPath = ServletRequestPathUtils.getParsedRequestPath(request);
        // 遍历预定义的企业作用域路径模式，提取 enterpriseId 路径变量。
        for (PathPattern pattern : ENTERPRISE_PATTERNS) {
            PathPattern.PathMatchInfo matchInfo = pattern.matchAndExtract(requestPath);
            if (matchInfo != null) {
                // 路径变量提取后转为 Long；非数字值（如 "abc"）返回 null，
                // 由调用方放行，后续由 Controller 的 @PathVariable 类型转换失败处理。
                return parseEnterpriseId(matchInfo.getUriVariables().get("enterpriseId"));
            }
        }
        return null;
    }

    /** 解析企业 ID：null、空白或非数字均返回 null。 */
    private Long parseEnterpriseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 写出统一 {@link Result} 错误响应（与 RestAuthenticationEntryPoint 同一模式）。 */
    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), Result.failure(errorCode));
    }
}
