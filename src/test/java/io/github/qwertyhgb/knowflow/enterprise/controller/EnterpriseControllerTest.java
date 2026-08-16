package io.github.qwertyhgb.knowflow.enterprise.controller;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseMemberStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseService;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseMemberVO;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseVO;
import io.github.qwertyhgb.knowflow.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnterpriseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EnterpriseService enterpriseService;

    @MockitoBean
    private TokenService tokenService;

    /**
     * EnterpriseContextFilter 校验企业上下文时查询成员关系，
     * 默认桩为正常 OWNER 成员，满足全部企业作用域请求的过滤器校验；
     * 个别测试（如 filter 拒绝场景）会覆盖此桩。
     * {@code @MockitoBean} 默认宽松模式，桩在 401 等未用到的测试中不会报错。
     */
    @MockitoBean
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @BeforeEach
    void stubEnterpriseContext() {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(10L);
        member.setUserId(1L);
        member.setMemberRole(EnterpriseMemberRole.OWNER);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);
    }

    @Test
    void shouldCreateEnterpriseWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.createEnterprise(org.mockito.ArgumentMatchers.eq(1L),
                any(EnterpriseCreateRequest.class))).thenReturn(enterpriseVO());

        mockMvc.perform(post("/api/enterprises")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": " 测试企业 "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("测试企业"))
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-15T08:00:14.471Z"));

        verify(enterpriseService).createEnterprise(org.mockito.ArgumentMatchers.eq(1L),
                any(EnterpriseCreateRequest.class));
    }

    @Test
    void shouldReturnUnauthorizedWhenCreatingWithoutToken() throws Exception {
        mockMvc.perform(post("/api/enterprises")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "测试企业"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldRejectBlankName() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(post("/api/enterprises")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldRejectNameLongerThan100Characters() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(post("/api/enterprises")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s"
                                }
                                """.formatted("长".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldListMyEnterprisesWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.listMyEnterprises(1L)).thenReturn(List.of(enterpriseVO()));

        mockMvc.perform(get("/api/enterprises")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("测试企业"))
                .andExpect(jsonPath("$.data[0].status").value("NORMAL"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-08-15T08:00:14.471Z"));

        verify(enterpriseService).listMyEnterprises(1L);
    }

    @Test
    void shouldReturnEmptyArrayWhenUserHasNoEnterprises() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.listMyEnterprises(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/enterprises")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void shouldReturnUnauthorizedWhenListingWithoutToken() throws Exception {
        mockMvc.perform(get("/api/enterprises"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldReturnEnterpriseDetailForActiveMember() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.getEnterpriseDetail(1L, 10L)).thenReturn(enterpriseVO());

        mockMvc.perform(get("/api/enterprises/10")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("测试企业"))
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-15T08:00:14.471Z"));

        verify(enterpriseService).getEnterpriseDetail(1L, 10L);
    }

    @Test
    void shouldReturnForbiddenWhenUserIsNotMember() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.getEnterpriseDetail(1L, 10L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(get("/api/enterprises/10")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("没有操作权限"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldReturnNotFoundWhenEnterpriseDoesNotExist() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.getEnterpriseDetail(1L, 99L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/enterprises/99")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldReturnForbiddenWhenMemberIsDisabled() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.getEnterpriseDetail(1L, 10L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(get("/api/enterprises/10")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnUnauthorizedWhenGettingDetailWithoutToken() throws Exception {
        mockMvc.perform(get("/api/enterprises/10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldUpdateEnterpriseNameWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.updateEnterprise(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), any(EnterpriseUpdateRequest.class)))
                .thenReturn(enterpriseVO());

        mockMvc.perform(put("/api/enterprises/10")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": " 新企业名 "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("测试企业"))
                .andExpect(jsonPath("$.data.status").value("NORMAL"));

        mockMvc.perform(post("/api/enterprises/10/members/8/remove")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(enterpriseService).removeMember(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(8L));
    }

    @Test
    void shouldReturnUnauthorizedWhenRemovingWithoutToken() throws Exception {
        mockMvc.perform(post("/api/enterprises/10/members/8/remove"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldRejectWhenRemovingReturnsForbidden() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(enterpriseService).removeMember(org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(8L));

        mockMvc.perform(post("/api/enterprises/10/members/8/remove")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void shouldRejectWhenRemovingReturnsBusinessError() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        doThrow(new BusinessException(ErrorCode.CANNOT_REMOVE_OWNER))
                .when(enterpriseService).removeMember(org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(8L));

        mockMvc.perform(post("/api/enterprises/10/members/8/remove")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_OWNER"))
                .andExpect(jsonPath("$.message").value("企业所有者不能被移除或禁用"));
    }

    // -------------------- 修改成员状态 --------------------

    @Test
    void shouldUpdateMemberStatusWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.updateMemberStatus(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(8L),
                any(EnterpriseMemberStatusUpdateRequest.class))).thenReturn(disabledMemberVO());

        mockMvc.perform(put("/api/enterprises/10/members/8/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "DISABLED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userId").value(8))
                .andExpect(jsonPath("$.data.email").value("bob@example.com"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        verify(enterpriseService).updateMemberStatus(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(8L),
                any(EnterpriseMemberStatusUpdateRequest.class));
    }

    @Test
    void shouldReturnUnauthorizedWhenUpdatingStatusWithoutToken() throws Exception {
        mockMvc.perform(put("/api/enterprises/10/members/8/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "DISABLED"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldRejectMissingStatusWhenUpdating() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(put("/api/enterprises/10/members/8/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldRejectUnknownStatusValue() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // "FROZEN" 不是合法枚举：Jackson 反序列化失败 → 400 INVALID_PARAMETER。
        mockMvc.perform(put("/api/enterprises/10/members/8/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "FROZEN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldReturnForbiddenWhenUpdatingOwnStatus() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.updateMemberStatus(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(1L),
                any(EnterpriseMemberStatusUpdateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.SELF_REMOVE_NOT_ALLOWED));

        mockMvc.perform(put("/api/enterprises/10/members/1/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "DISABLED"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REMOVE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("您不能从企业中移除自己"));
    }

    @Test
    void shouldReturnBadRequestWhenModifyingOwnerStatus() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.updateMemberStatus(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(7L),
                any(EnterpriseMemberStatusUpdateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.CANNOT_REMOVE_OWNER));

        mockMvc.perform(put("/api/enterprises/10/members/7/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "DISABLED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_OWNER"))
                .andExpect(jsonPath("$.message").value("企业所有者不能被移除或禁用"));
    }

    @Test
    void shouldReturnForbiddenWhenUpdatingWithoutPermission() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.updateEnterprise(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), any(EnterpriseUpdateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(put("/api/enterprises/10")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "新企业名"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("没有操作权限"));
    }

    @Test
    void shouldRejectBlankNameWhenUpdating() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        mockMvc.perform(put("/api/enterprises/10")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldListMembersWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.listMembers(1L, 10L)).thenReturn(List.of(memberVO()));

        mockMvc.perform(get("/api/enterprises/10/members")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(7))
                .andExpect(jsonPath("$.data[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$.data[0].nickname").value("Alice"))
                .andExpect(jsonPath("$.data[0].role").value("OWNER"))
                .andExpect(jsonPath("$.data[0].status").value("NORMAL"))
                .andExpect(jsonPath("$.data[0].joinedAt").value("2026-08-15T08:00:14.471Z"));

        verify(enterpriseService).listMembers(1L, 10L);
    }

    @Test
    void shouldReturnForbiddenWhenNonMemberListsMembers() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.listMembers(1L, 10L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(get("/api/enterprises/10/members")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("没有操作权限"));
    }

    @Test
    void shouldReturnNotFoundWhenListingMembersOfNonExistentEnterprise() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        when(enterpriseService.listMembers(1L, 99L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/enterprises/99/members")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"));
    }

    @Test
    void shouldReturnUnauthorizedWhenListingMembersWithoutToken() throws Exception {
        mockMvc.perform(get("/api/enterprises/10/members"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(enterpriseService);
    }

    // -------------------- 企业上下文过滤器 --------------------

    @Test
    void shouldRejectWhenEnterpriseContextHeaderMissing() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // 强制模式：企业作用域请求未携带 X-Enterprise-Id → 400，且不会进入业务层。
        mockMvc.perform(get("/api/enterprises/10")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldRejectWhenEnterpriseContextMismatchesPath() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));

        // 请求头上下文是 99、路径目标是 10：防止跨企业上下文调用。
        mockMvc.perform(get("/api/enterprises/10")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISMATCH"));

        verifyNoInteractions(enterpriseService);
    }

    @Test
    void shouldRejectWhenUserIsNotMemberOfContextEnterprise() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(1L));
        // 覆盖 @BeforeEach 的默认桩：当前用户不是目标企业的正常成员。
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(get("/api/enterprises/10")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(enterpriseService);
    }

    private EnterpriseVO enterpriseVO() {
        Enterprise enterprise = new Enterprise();
        enterprise.setId(1L);
        enterprise.setName("测试企业");
        enterprise.setSlug("abc123def456");
        enterprise.setStatus(EnterpriseStatus.NORMAL);
        // 带纳秒精度的时间，验证序列化层统一收敛为毫秒精度 ISO-8601 UTC。
        enterprise.setCreatedAt(Instant.parse("2026-08-15T08:00:14.471987654Z"));
        enterprise.setUpdatedAt(Instant.parse("2026-08-15T08:00:14.471987654Z"));
        return EnterpriseVO.from(enterprise);
    }

    private EnterpriseMemberVO memberVO() {
        EnterpriseMember member = new EnterpriseMember();
        member.setId(1L);
        member.setEnterpriseId(10L);
        member.setUserId(7L);
        member.setMemberRole(EnterpriseMemberRole.OWNER);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        // 带纳秒精度的时间，验证序列化层统一收敛为毫秒精度 ISO-8601 UTC。
        member.setJoinedAt(Instant.parse("2026-08-15T08:00:14.471987654Z"));

        User user = new User();
        user.setEmail("alice@example.com");
        user.setNickname("Alice");
        return EnterpriseMemberVO.from(member, user);
    }

    /** 构造一个被禁用成员的视图：状态 DISABLED，关联 bob 的公开信息。 */
    private EnterpriseMemberVO disabledMemberVO() {
        EnterpriseMember member = new EnterpriseMember();
        member.setId(2L);
        member.setEnterpriseId(10L);
        member.setUserId(8L);
        member.setMemberRole(EnterpriseMemberRole.MEMBER);
        member.setStatus(EnterpriseMemberStatus.DISABLED);
        member.setJoinedAt(Instant.parse("2026-08-15T08:00:14.471987654Z"));

        User user = new User();
        user.setEmail("bob@example.com");
        user.setNickname("Bob");
        return EnterpriseMemberVO.from(member, user);
    }
}
