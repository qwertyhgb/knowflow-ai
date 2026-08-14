package io.github.qwertyhgb.knowflow.auth.token;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * 从请求的 {@code Authorization} 头解析 Bearer Token。
 *
 * <p>认证过滤器与需要原始 Token 的接口（如登出）共用，避免提取逻辑在多处漂移。</p>
 */
public final class BearerTokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    private BearerTokenExtractor() {
    }

    /**
     * 提取 Bearer Token。
     *
     * @param request HTTP 请求
     * @return Token；请求头缺失、格式不正确或内容为空时为 {@code null}
     */
    public static String extract(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? null : token;
    }
}
