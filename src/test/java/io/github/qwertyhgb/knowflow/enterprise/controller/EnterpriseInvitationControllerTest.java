package io.github.qwertyhgb.knowflow.enterprise.controller;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseInvitationCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseInvitation;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseInvitationStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseInvitationService;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseInvitationVO;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 创建成员邀请接口的 Web 层测试：认证、参数校验、枚举反序列化
 * 与统一错误响应结构；业务规则由 Service 单元测试覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnterpriseInvitationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EnterpriseInvitationService enterpriseInvitationService;

    @MockitoBean
    private TokenService tokenService;

    /**
     * EnterpriseContextFilter 校验企业上下文时查询成员关系：
     * 默认桩为正常 OWNER 成员，满足本类全部企业作用域请求（宽松模式，
     * 401 等未用到的测试不受影响）。
     */
    @MockitoBean
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @BeforeEach
    void stubEnterpriseContext() {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(1L);
        member.setUserId(7L);
        member.setMemberRole(EnterpriseMemberRole.OWNER);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);
    }

    @Test
    void shouldCreateInvitationWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.createInvitation(eq(7L), eq(1L),
                any(EnterpriseInvitationCreateRequest.class))).thenReturn(invitationVO());

        mockMvc.perform(post("/api/enterprises/1/invitations")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "invitee@example.com",
                                  "role": "MEMBER"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.enterpriseId").value(1))
                .andExpect(jsonPath("$.data.inviteeEmail").value("invitee@example.com"))
                .andExpect(jsonPath("$.data.role").value("MEMBER"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-08-22T08:00:00Z"))
                .andExpect(jsonPath("$.data.token").value("tokentokentokentokentokentoken123456"));

        verify(enterpriseInvitationService).createInvitation(eq(7L), eq(1L),
                any(EnterpriseInvitationCreateRequest.class));
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/enterprises/1/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "invitee@example.com",
                                  "role": "MEMBER"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldRejectInvalidEmailFormat() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/enterprises/1/invitations")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "role": "MEMBER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldRejectBlankEmail() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/enterprises/1/invitations")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "   ",
                                  "role": "MEMBER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldRejectMissingRole() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/enterprises/1/invitations")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "invitee@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldRejectUnknownRoleValue() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        // "SUPERADMIN" 不是合法的枚举取值：Jackson 反序列化失败 → HttpMessageNotReadable
        // → 全局异常处理器统一转为 400 INVALID_PARAMETER（不进入业务层）。
        mockMvc.perform(post("/api/enterprises/1/invitations")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "invitee@example.com",
                                  "role": "SUPERADMIN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldReturnConflictWhenPendingInvitationExists() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.createInvitation(eq(7L), eq(1L),
                any(EnterpriseInvitationCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.INVITATION_ALREADY_PENDING));

        mockMvc.perform(post("/api/enterprises/1/invitations")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "invitee@example.com",
                                  "role": "MEMBER"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_PENDING"))
                .andExpect(jsonPath("$.message").value("该邮箱已存在待接受的邀请"));
    }

    @Test
    void shouldListInvitationsWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.listEnterpriseInvitations(7L, 1L))
                .thenReturn(List.of(invitationVO()));

        mockMvc.perform(get("/api/enterprises/1/invitations")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.data[0].enterpriseId").value(1))
                .andExpect(jsonPath("$.data[0].inviteeEmail").value("invitee@example.com"))
                .andExpect(jsonPath("$.data[0].role").value("MEMBER"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2026-08-22T08:00:00Z"));

        verify(enterpriseInvitationService).listEnterpriseInvitations(7L, 1L);
    }

    @Test
    void shouldReturnUnauthorizedWhenListingInvitationsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/enterprises/1/invitations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldRevokeInvitationWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.revokeInvitation(7L, 1L, 100L)).thenReturn(revokedVO());

        mockMvc.perform(post("/api/enterprises/1/invitations/100/revoke")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        verify(enterpriseInvitationService).revokeInvitation(7L, 1L, 100L);
    }

    @Test
    void shouldReturnUnauthorizedWhenRevokingWithoutToken() throws Exception {
        mockMvc.perform(post("/api/enterprises/1/invitations/100/revoke"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldReturnNotFoundWhenRevokingUnknownInvitation() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.revokeInvitation(7L, 1L, 999L))
                .thenThrow(new BusinessException(ErrorCode.INVITATION_NOT_FOUND));

        mockMvc.perform(post("/api/enterprises/1/invitations/999/revoke")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("邀请不存在"));
    }

    /** 构造一个创建成功的邀请视图：状态 PENDING、过期时间为固定时间点、含一次性明文令牌。 */
    private EnterpriseInvitationVO invitationVO() {
        EnterpriseInvitation invitation = new EnterpriseInvitation();
        invitation.setId(100L);
        invitation.setEnterpriseId(1L);
        invitation.setInviterUserId(7L);
        invitation.setInviteeEmail("invitee@example.com");
        invitation.setMemberRole(EnterpriseMemberRole.MEMBER);
        invitation.setStatus(EnterpriseInvitationStatus.PENDING);
        invitation.setExpiresAt(Instant.parse("2026-08-22T08:00:00Z"));
        invitation.setCreatedAt(Instant.parse("2026-08-15T08:00:00Z"));
        return EnterpriseInvitationVO.from(invitation, "tokentokentokentokentokentoken123456", null);
    }

    /** 构造一个已撤销的邀请视图：状态 REVOKED、无令牌。 */
    private EnterpriseInvitationVO revokedVO() {
        EnterpriseInvitation invitation = new EnterpriseInvitation();
        invitation.setId(100L);
        invitation.setEnterpriseId(1L);
        invitation.setInviterUserId(7L);
        invitation.setInviteeEmail("invitee@example.com");
        invitation.setMemberRole(EnterpriseMemberRole.MEMBER);
        invitation.setStatus(EnterpriseInvitationStatus.REVOKED);
        invitation.setExpiresAt(Instant.parse("2026-08-22T08:00:00Z"));
        invitation.setCreatedAt(Instant.parse("2026-08-15T08:00:00Z"));
        return EnterpriseInvitationVO.from(invitation, null, null);
    }
}
