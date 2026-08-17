package io.github.qwertyhgb.knowflow.auth.filter;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.auth.token.BearerTokenExtractor;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从请求头解析 Bearer Token 并查询登录态的认证过滤器。
 *
 * <p>继承 {@link OncePerRequestFilter}，保证一次请求分派只执行一次。
 * 仅当安全上下文中尚无认证信息时才尝试认证，避免覆盖其他认证机制建立的上下文。</p>
 *
 * <p>认证成功后会顺带调用 {@link TokenService#renewToken(String)} 续期，
 * 实现「活跃用户保持登录」。续期失败不影响主流程，仅记 warn 日志。</p>
 */
@Slf4j
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokenService;

    public TokenAuthenticationFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        // ========== 1. 提取 Bearer Token ==========
        // 从 Authorization: Bearer {token} 请求头中提取 token 字符串。
        // 请求头缺失或格式不正确时返回 null，后续流程直接跳过认证。
        String token = BearerTokenExtractor.extract(request);

        // ========== 2. 尝试建立认证 ==========
        // 两个条件必须同时满足：
        //   a) token 存在（请求携带了合法的 Bearer 凭证）
        //   b) 当前 SecurityContext 中尚无认证信息
        // 条件 b 是防御性检查：避免覆盖 FilterChain 中其他过滤器（如已通过 SSO 或 JWT 认证的）
        // 已建立的认证上下文。本项目虽然只有一种认证方式，但保留此检查是标准做法。
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            // resolveUserId 通过 Redis 查询 token 对应的用户 ID：
            //   - Redis key: auth:token:{token}
            //   - Redis value: userId（字符串形式）
            // 返回 Optional<Long>，不存在或已过期时返回 Optional.empty()，认证不通过。
            tokenService.resolveUserId(token).ifPresent(userId -> {

                // ========== 3. 构建认证主体 ==========
                // 主体统一使用 EnterpriseUser（Java 21 record 类型），
                // 此时仅填入 userId，不涉及企业上下文（enterpriseId 和 roleCode 为 null）。
                // 企业作用域相关的上下文由后置的 EnterpriseContextFilter 负责注入。
                // authorities 传入空列表（List.of()），因为当前阶段还不知道用户权限；
                // 企业作用域请求的权限码会在 EnterpriseContextFilter 中注入。
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                EnterpriseUser.withoutEnterprise(userId), null, List.of());

                // ========== 4. 写入 SecurityContext ==========
                // 使用 createEmptyContext() 而非直接复用 SecurityContextHolder.getContext()：
                // 避免多线程环境下原有的 SecurityContext 被意外修改（SecurityContextHolder 默认
                // 使用 ThreadLocal 存储，每个请求线程独立，但防御性创建新上下文仍是推荐做法）。
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);

                // ========== 5. 按需续期 ==========
                // 续期是顺带行为：Redis 瞬断不应影响已建立的认证状态。
                // 详见 renewTokenSafely 方法。
                renewTokenSafely(token);
            });
        }

        // ========== 6. 放行 ==========
        // 无论认证成功与否，都放行请求——未认证的请求会由 SecurityConfig 配置的
        // RestAuthenticationEntryPoint 返回 401；已认证的请求走到后续过滤器和 Controller。
        chain.doFilter(request, response);
    }

    /**
     * 续期失败仅记日志，不抛异常，避免影响已建立的认证状态与主请求流程。
     */
    private void renewTokenSafely(String token) {
        try {
            tokenService.renewToken(token);
        } catch (RuntimeException ex) {
            // 不记录异常 message，避免把连接信息等非白名单内容写入日志。
            log.warn("event=token_renew_failed errorType={}", ex.getClass().getSimpleName());
        }
    }
}
