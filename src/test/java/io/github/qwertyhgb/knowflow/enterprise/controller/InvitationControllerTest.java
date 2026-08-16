package io.github.qwertyhgb.knowflow.enterprise.controller;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.InvitationAcceptRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseInvitation;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseInvitationStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseInvitationService;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseInvitationVO;
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
 * 接受邀请接口的 Web 层测试：认证、参数校验与统一错误响应结构；
 * 业务规则（状态机、邮箱匹配等）由 Service 单元测试覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EnterpriseInvitationService enterpriseInvitationService;

    @MockitoBean
    private TokenService tokenService;

    @Test
    void shouldAcceptInvitationWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.acceptInvitation(eq(7L),
                any(InvitationAcceptRequest.class))).thenReturn(acceptedVO());

        mockMvc.perform(post("/api/invitations/accept")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "tokentokentokentokentokentoken123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.enterpriseId").value(1))
                .andExpect(jsonPath("$.data.inviteeEmail").value("invitee@example.com"))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.acceptedAt").value("2026-08-15T08:00:00Z"));

        verify(enterpriseInvitationService).acceptInvitation(eq(7L),
                any(InvitationAcceptRequest.class));
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "tokentokentokentokentokentoken123456"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldRejectBlankToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/invitations/accept")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldRejectTokenShorterThan32Characters() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/invitations/accept")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "too-short"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    @Test
    void shouldReturnNotFoundWhenTokenCannotLocateInvitation() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.acceptInvitation(eq(7L),
                any(InvitationAcceptRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.INVITATION_NOT_FOUND));

        mockMvc.perform(post("/api/invitations/accept")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "tokentokentokentokentokentoken123456"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("邀请不存在"));
    }

    @Test
    void shouldReturnExpiredWhenInvitationIsPastExpiry() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.acceptInvitation(eq(7L),
                any(InvitationAcceptRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.INVITATION_EXPIRED));

        mockMvc.perform(post("/api/invitations/accept")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "tokentokentokentokentokentoken123456"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"))
                .andExpect(jsonPath("$.message").value("邀请已过期"));
    }

    @Test
    void shouldReturnForbiddenWhenLoginEmailMismatches() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.acceptInvitation(eq(7L),
                any(InvitationAcceptRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.INVITATION_EMAIL_MISMATCH));

        mockMvc.perform(post("/api/invitations/accept")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "tokentokentokentokentokentoken123456"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_EMAIL_MISMATCH"))
                .andExpect(jsonPath("$.message").value("当前登录邮箱与被邀请邮箱不一致"));
    }

    @Test
    void shouldListMyPendingInvitationsWithValidToken() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(enterpriseInvitationService.listMyPendingInvitations(7L))
                .thenReturn(List.of(pendingVO()));

        mockMvc.perform(get("/api/invitations/my")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.data[0].enterpriseId").value(1))
                .andExpect(jsonPath("$.data[0].enterpriseName").value("测试企业"))
                .andExpect(jsonPath("$.data[0].inviteeEmail").value("invitee@example.com"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2026-08-22T08:00:00Z"));

        verify(enterpriseInvitationService).listMyPendingInvitations(7L);
    }

    @Test
    void shouldReturnUnauthorizedWhenListingMyInvitationsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/invitations/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(enterpriseInvitationService);
    }

    /** 构造一条待处理邀请视图：状态 PENDING、带企业名称、无令牌。 */
    private EnterpriseInvitationVO pendingVO() {
        EnterpriseInvitation invitation = new EnterpriseInvitation();
        invitation.setId(100L);
        invitation.setEnterpriseId(1L);
        invitation.setInviterUserId(7L);
        invitation.setInviteeEmail("invitee@example.com");
        invitation.setMemberRole(EnterpriseMemberRole.MEMBER);
        invitation.setStatus(EnterpriseInvitationStatus.PENDING);
        invitation.setExpiresAt(Instant.parse("2026-08-22T08:00:00Z"));
        invitation.setCreatedAt(Instant.parse("2026-08-15T08:00:00Z"));
        return EnterpriseInvitationVO.from(invitation, null, "测试企业");
    }

    /** 构造一个接受成功的邀请视图：状态 ACCEPTED、含接受时间、无令牌。 */
    private EnterpriseInvitationVO acceptedVO() {
        EnterpriseInvitation invitation = new EnterpriseInvitation();
        invitation.setId(100L);
        invitation.setEnterpriseId(1L);
        invitation.setInviterUserId(7L);
        invitation.setInviteeEmail("invitee@example.com");
        invitation.setMemberRole(EnterpriseMemberRole.MEMBER);
        invitation.setStatus(EnterpriseInvitationStatus.ACCEPTED);
        invitation.setExpiresAt(Instant.parse("2026-08-22T08:00:00Z"));
        invitation.setCreatedAt(Instant.parse("2026-08-15T08:00:00Z"));
        invitation.setAcceptedAt(Instant.parse("2026-08-15T08:00:00Z"));
        return EnterpriseInvitationVO.from(invitation, null, null);
    }
}
