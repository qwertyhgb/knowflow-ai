package io.github.qwertyhgb.knowflow.auth.filter;

import io.github.qwertyhgb.knowflow.auth.token.BearerTokenExtractor;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
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
                // 暂无角色/权限体系，authorities 为空；后续接入权限时再扩展。
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
