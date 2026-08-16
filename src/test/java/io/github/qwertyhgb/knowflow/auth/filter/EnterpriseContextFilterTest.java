package io.github.qwertyhgb.knowflow.auth.filter;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 企业上下文过滤器单元测试：覆盖放行、400（缺失/不一致）、403（非成员）
 * 与上下文注入等全部规则分支；与 TokenAuthenticationFilterTest 同一测试模式
 * （MockHttpServletRequest + 直接调用 doFilter + 断言 SecurityContext）。
 */
@ExtendWith(MockitoExtension.class)
class EnterpriseContextFilterTest {

    @Mock
    private EnterpriseMemberMapper enterpriseMemberMapper;

    /** 过滤器错误响应序列化用真实 JsonMapper，便于断言响应体内容。 */
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * 注意：不能在字段初始化器中构造 filter——字段初始化发生在 MockitoExtension
     * 注入 @Mock 之前，会把 null 传进去；统一放到 setUp 中构造。
     */
    private EnterpriseContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new EnterpriseContextFilter(enterpriseMemberMapper, jsonMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldPassThroughWhenNotAuthenticated() throws Exception {
        // 未认证请求直接放行走 401：先认证，再谈企业上下文。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = (servletRequest, servletResponse) -> chainInvocations.incrementAndGet();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(1, chainInvocations.get());
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    @Test
    void shouldPassThroughForNonEnterprisePath() throws Exception {
        // /api/users/** 与 /api/invitations/** 不属于企业作用域，无需上下文。
        authenticateAs(7L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/users/me");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = (servletRequest, servletResponse) -> chainInvocations.incrementAndGet();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(1, chainInvocations.get());
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    @Test
    void shouldPassThroughCreateAndListEnterprisePaths() throws Exception {
        // /api/enterprises（创建/列表）不含路径企业 ID，不属于企业作用域。
        authenticateAs(7L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = (servletRequest, servletResponse) -> chainInvocations.incrementAndGet();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(1, chainInvocations.get());
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectWhenHeaderMissing() throws Exception {
        authenticateAs(7L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10/members");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("ENTERPRISE_CONTEXT_MISSING"),
                "应返回缺少企业上下文的统一错误码");
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectWhenHeaderNotNumeric() throws Exception {
        authenticateAs(7L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10");
        request.addHeader(EnterpriseContextFilter.ENTERPRISE_ID_HEADER, "not-a-number");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("ENTERPRISE_CONTEXT_MISSING"));
    }

    @Test
    void shouldRejectWhenHeaderMismatchesPath() throws Exception {
        // 请求头是 99、路径是 10：防止以 A 企业上下文调用 B 企业接口。
        authenticateAs(7L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10");
        request.addHeader(EnterpriseContextFilter.ENTERPRISE_ID_HEADER, "99");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("ENTERPRISE_CONTEXT_MISMATCH"),
                "请求头与路径企业不一致应返回 MISMATCH");
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectWhenUserIsNotMemberOfContextEnterprise() throws Exception {
        authenticateAs(7L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10");
        request.addHeader(EnterpriseContextFilter.ENTERPRISE_ID_HEADER, "10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(403, response.getStatus(), "非目标企业正常成员应返回 403");
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
    }

    @Test
    void shouldInjectEnterpriseContextWhenValid() throws Exception {
        authenticateAs(7L);
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(10L, 7L, EnterpriseMemberRole.OWNER));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10/members");
        request.addHeader(EnterpriseContextFilter.ENTERPRISE_ID_HEADER, "10");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = (servletRequest, servletResponse) -> chainInvocations.incrementAndGet();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(1, chainInvocations.get());
        EnterpriseUser principal =
                (EnterpriseUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(7L, principal.userId());
        assertEquals(10L, principal.currentEnterpriseId(), "校验通过后应注入当前企业 ID");
        assertEquals(EnterpriseMemberRole.OWNER, principal.currentRole(), "应同时注入成员角色");
    }

    @Test
    void shouldPassThroughWhenPrincipalIsNotEnterpriseUser() throws Exception {
        // 防御性分支：主体不是 EnterpriseUser（如测试或其它认证机制）时直接放行。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("legacy-principal", null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = (servletRequest, servletResponse) -> chainInvocations.incrementAndGet();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(1, chainInvocations.get());
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    /** 构造已通过 Token 认证的上下文（主体无企业上下文）。 */
    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        EnterpriseUser.withoutEnterprise(userId), null, List.of()));
    }

    private EnterpriseMember member(Long enterpriseId, Long userId, EnterpriseMemberRole role) {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(enterpriseId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        return member;
    }
}
