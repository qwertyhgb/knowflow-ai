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
        String token = BearerTokenExtractor.extract(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            tokenService.resolveUserId(token).ifPresent(userId -> {
                // 主体统一为 EnterpriseUser：此时只有 userId、无企业上下文；
                // 企业作用域请求随后由 EnterpriseContextFilter 校验并注入企业上下文。
                // 暂无额外的授权条目，authorities 为空；后续接入权限时再扩展。
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                EnterpriseUser.withoutEnterprise(userId), null, List.of());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                // 续期是顺带行为，Redis 抖动不应影响认证主流程。
                renewTokenSafely(token);
            });
        }
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
