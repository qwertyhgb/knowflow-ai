package io.github.qwertyhgb.knowflow.auth.filter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.server.RequestPath;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 *   <li>校验通过 → 重建认证主体，填入 {@code currentEnterpriseId} 与成员角色，
 *       下游 Controller 可从 {@link EnterpriseUser} 直接读取。</li>
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

    private final JsonMapper jsonMapper;

    public EnterpriseContextFilter(EnterpriseMemberMapper enterpriseMemberMapper, JsonMapper jsonMapper) {
        this.enterpriseMemberMapper = enterpriseMemberMapper;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 1. 尚未认证：直接放行，让认证入口点返回 401——先认证，再谈企业上下文。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof EnterpriseUser currentUser)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. 从路径解析目标企业 ID；非企业作用域请求（模式不匹配）原样放行。
        Long pathEnterpriseId = resolvePathEnterpriseId(request);
        if (pathEnterpriseId == null) {
            chain.doFilter(request, response);
            return;
        }

        // 3. 企业作用域请求必须携带上下文请求头。
        Long headerEnterpriseId = parseEnterpriseId(request.getHeader(ENTERPRISE_ID_HEADER));
        if (headerEnterpriseId == null) {
            writeError(response, ErrorCode.ENTERPRISE_CONTEXT_MISSING);
            return;
        }

        // 4. 请求头必须与路径目标企业一致，否则拒绝，防止跨企业上下文调用。
        if (!headerEnterpriseId.equals(pathEnterpriseId)) {
            writeError(response, ErrorCode.ENTERPRISE_CONTEXT_MISMATCH);
            return;
        }

        // 5. 成员身份校验：必须是目标企业的正常成员（含角色，供主体注入）。
        EnterpriseMember member = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, headerEnterpriseId)
                        .eq(EnterpriseMember::getUserId, currentUser.userId())
                        .eq(EnterpriseMember::getStatus, EnterpriseMemberStatus.NORMAL));
        if (member == null) {
            // 日志只记录系统标识：上下文非法属于可预期的异常输入，记 WARN 不记堆栈。
            log.warn("event=enterprise_context_rejected enterpriseId={} userId={}",
                    headerEnterpriseId, currentUser.userId());
            writeError(response, ErrorCode.FORBIDDEN);
            return;
        }

        // 6. 校验通过：重建认证主体注入企业上下文（原主体不可变，不做就地修改）。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        currentUser.withEnterprise(headerEnterpriseId, member.getMemberRole()),
                        null,
                        authentication.getAuthorities()));
        chain.doFilter(request, response);
    }

    /**
     * 从请求路径解析目标企业 ID；不匹配企业作用域模式时返回 null。
     *
     * <p>路径变量可能是非数字（如 {@code /api/enterprises/abc}），
     * 解析失败同样返回 null 放行——该请求随后会因 Controller 的
     * {@code @PathVariable Long} 类型转换失败被全局异常处理兜底，
     * 保持与引入本过滤器前一致的行为。</p>
     */
    private Long resolvePathEnterpriseId(HttpServletRequest request) {
        // 安全过滤器链先于 DispatcherServlet 执行，解析结果缓存属性通常尚未写入；
        // hasParsedRequestPath 判断后按需 parseAndCache（解析并缓存，后续环节可复用）。
        if (!ServletRequestPathUtils.hasParsedRequestPath(request)) {
            ServletRequestPathUtils.parseAndCache(request);
        }
        RequestPath requestPath = ServletRequestPathUtils.getParsedRequestPath(request);
        for (PathPattern pattern : ENTERPRISE_PATTERNS) {
            PathPattern.PathMatchInfo matchInfo = pattern.matchAndExtract(requestPath);
            if (matchInfo != null) {
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
