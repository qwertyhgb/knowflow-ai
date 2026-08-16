package io.github.qwertyhgb.knowflow.auth.filter;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenAuthenticationFilterTest {

    @Mock
    private TokenService tokenService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAuthenticateValidBearerToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "bEaReR valid-token");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = (servletRequest, servletResponse) -> chainInvocations.incrementAndGet();

        new TokenAuthenticationFilter(tokenService)
                .doFilter(request, new MockHttpServletResponse(), chain);

        // 主体为 EnterpriseUser：Token 认证阶段只有 userId，企业上下文留待
        // EnterpriseContextFilter 按需注入。
        EnterpriseUser principal =
                (EnterpriseUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(7L, principal.userId());
        assertNull(principal.currentEnterpriseId());
        assertEquals(1, chainInvocations.get());
        verify(tokenService).resolveUserId("valid-token");
    }

    @Test
    void shouldIgnoreBlankBearerToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer   ");

        new TokenAuthenticationFilter(tokenService)
                .doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenService, never()).resolveUserId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldNotOverwriteExistingAuthentication() throws Exception {
        UsernamePasswordAuthenticationToken existing =
                new UsernamePasswordAuthenticationToken(99L, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(existing);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");

        new TokenAuthenticationFilter(tokenService)
                .doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });

        assertEquals(99L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        verify(tokenService, never()).resolveUserId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldRenewTokenAfterSuccessfulAuthentication() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");

        new TokenAuthenticationFilter(tokenService)
                .doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });

        verify(tokenService).renewToken("valid-token");
    }

    @Test
    void shouldNotRenewInvalidToken() throws Exception {
        when(tokenService.resolveUserId("invalid-token")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid-token");

        new TokenAuthenticationFilter(tokenService)
                .doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> { });

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenService, never()).renewToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldContinueRequestWhenRenewTokenFails() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        org.mockito.Mockito.doThrow(new RuntimeException("Redis down"))
                .when(tokenService).renewToken("valid-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = (servletRequest, servletResponse) -> chainInvocations.incrementAndGet();

        new TokenAuthenticationFilter(tokenService)
                .doFilter(request, new MockHttpServletResponse(), chain);

        // 认证仍应成功建立，主请求流程不应被续期失败影响。
        EnterpriseUser principal =
                (EnterpriseUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(7L, principal.userId());
        assertEquals(1, chainInvocations.get());
    }
}
