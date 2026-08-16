package io.github.qwertyhgb.knowflow.auth.filter;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRolePermissionMapper;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * 企业上下文过滤器单元测试：覆盖放行、400（缺失/不一致）、403（非成员/角色缺失或禁用）
 * 与上下文注入等全部规则分支；与 TokenAuthenticationFilterTest 同一测试模式
 * （MockHttpServletRequest + 直接调用 doFilter + 断言 SecurityContext）。
 */
@ExtendWith(MockitoExtension.class)
class EnterpriseContextFilterTest {

    @Mock
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @Mock
    private EnterpriseRoleMapper enterpriseRoleMapper;

    @Mock
    private EnterpriseRolePermissionMapper enterpriseRolePermissionMapper;

    /** 过滤器错误响应序列化用真实 JsonMapper，便于断言响应体内容。 */
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * 注意：不能在字段初始化器中构造 filter——字段初始化发生在 MockitoExtension
     * 注入 @Mock 之前，会把 null 传进去；统一放到 setUp 中构造。
     */
    private EnterpriseContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new EnterpriseContextFilter(
                enterpriseMemberMapper, enterpriseRoleMapper, enterpriseRolePermissionMapper, jsonMapper);
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
        // 拒绝分支不应加载权限，避免把权限注入到未被授权的主体。
        verify(enterpriseRolePermissionMapper, never()).selectPermissionCodesByRoleId(any());
    }

    @Test
    void shouldInjectEnterpriseContextAndPermissionsWhenValid() throws Exception {
        authenticateAs(7L);
        EnterpriseMember member = member(10L, 7L, 100L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role(100L, 10L, "OWNER"));
        when(enterpriseRolePermissionMapper.selectPermissionCodesByRoleId(100L))
                .thenReturn(List.of("member:view", "member:remove"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10/members");
        request.addHeader(EnterpriseContextFilter.ENTERPRISE_ID_HEADER, "10");
        AtomicInteger chainInvocations = new AtomicInteger();
        FilterChain chain = (servletRequest, servletResponse) -> chainInvocations.incrementAndGet();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(1, chainInvocations.get());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        EnterpriseUser principal = (EnterpriseUser) auth.getPrincipal();
        assertEquals(7L, principal.userId());
        assertEquals(10L, principal.currentEnterpriseId(), "校验通过后应注入当前企业 ID");
        assertEquals("OWNER", principal.roleCode(), "应注入成员角色编码（enterprise_role.code）");
        // 权限码应注入到 authorities，供 @PreAuthorize("hasAuthority('member:remove')") 匹配。
        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertTrue(authorities.containsAll(List.of("member:view", "member:remove")),
                "校验通过后应把成员角色的权限码注入 authorities");
    }

    @Test
    void shouldRejectWhenMemberRoleIsMissing() throws Exception {
        // 防御性分支：成员存在但 role_id 为空（理论不发生，V6 已回填），按 403 拒绝且不注入权限。
        authenticateAs(7L);
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(10L, 7L, null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10");
        request.addHeader(EnterpriseContextFilter.ENTERPRISE_ID_HEADER, "10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(403, response.getStatus(), "成员缺少角色关联应按 403 拒绝");
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
        verify(enterpriseRoleMapper, never()).selectById(any());
        verify(enterpriseRolePermissionMapper, never()).selectPermissionCodesByRoleId(any());
    }

    @Test
    void shouldRejectWhenRoleDoesNotExist() throws Exception {
        // 成员关联的角色记录不存在（异常数据）：成员已无可用角色，按 403 拒绝。
        authenticateAs(7L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member(10L, 7L, 100L));
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10");
        request.addHeader(EnterpriseContextFilter.ENTERPRISE_ID_HEADER, "10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(403, response.getStatus(), "角色不存在应按 403 拒绝");
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
        verify(enterpriseRolePermissionMapper, never()).selectPermissionCodesByRoleId(any());
    }

    @Test
    void shouldRejectWhenRoleIsDisabled() throws Exception {
        // 成员关联的角色被禁用：不应再获得任何权限，按 403 拒绝。
        authenticateAs(7L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member(10L, 7L, 100L));
        when(enterpriseRoleMapper.selectById(100L))
                .thenReturn(role(100L, 10L, "ADMIN", EnterpriseRoleStatus.DISABLED));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/enterprises/10");
        request.addHeader(EnterpriseContextFilter.ENTERPRISE_ID_HEADER, "10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertEquals(403, response.getStatus(), "角色被禁用应按 403 拒绝");
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
        verify(enterpriseRolePermissionMapper, never()).selectPermissionCodesByRoleId(any());
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

    /** 构造正常状态的成员记录；roleId 可传 null（模拟 V6 前的异常数据）。 */
    private EnterpriseMember member(Long enterpriseId, Long userId, Long roleId) {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(enterpriseId);
        member.setUserId(userId);
        member.setRoleId(roleId);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        return member;
    }

    /** 构造正常状态的企业角色记录。 */
    private EnterpriseRole role(Long id, Long enterpriseId, String code) {
        return role(id, enterpriseId, code, EnterpriseRoleStatus.NORMAL);
    }

    private EnterpriseRole role(Long id, Long enterpriseId, String code, EnterpriseRoleStatus status) {
        EnterpriseRole role = new EnterpriseRole();
        role.setId(id);
        role.setEnterpriseId(enterpriseId);
        role.setCode(code);
        role.setName(code);
        role.setStatus(status);
        return role;
    }
}
