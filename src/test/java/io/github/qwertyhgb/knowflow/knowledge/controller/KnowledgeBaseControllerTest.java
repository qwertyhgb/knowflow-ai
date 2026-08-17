package io.github.qwertyhgb.knowflow.knowledge.controller;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRolePermissionMapper;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseCreateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseMemberAddRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseMemberRoleUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBase;
import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBaseMember;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseAccessMode;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseMemberRole;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseStatus;
import io.github.qwertyhgb.knowflow.knowledge.service.KnowledgeBaseService;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseMemberVO;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseVO;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 创建知识库接口的 Web 层测试：认证、企业上下文、成员校验、参数校验、时间格式和统一响应结构。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeBaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private EnterpriseMemberMapper enterpriseMemberMapper;

    /**
     * EnterpriseContextFilter 校验通过后按 roleId 查询成员角色（enterprise_role），
     * 默认桩返回正常 OWNER 角色，满足过滤器的主体重建。
     */
    @MockitoBean
    private EnterpriseRoleMapper enterpriseRoleMapper;

    /**
     * EnterpriseContextFilter 校验通过后注入权限码到 authorities。本接口「创建知识库」
     * 不加 {@code @PreAuthorize}（只要求成员身份），但过滤器仍会加载权限码；
     * 默认桩返回全部权限码，保持过滤器逻辑走通。
     */
    @MockitoBean
    private EnterpriseRolePermissionMapper enterpriseRolePermissionMapper;

    @BeforeEach
    void stubEnterpriseContext() {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(10L);
        member.setUserId(7L);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        // role_id 必须非空：V6 后成员必关联角色，过滤器据此加载权限码。
        member.setRoleId(100L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("OWNER"));
        when(enterpriseRolePermissionMapper.selectPermissionCodesByRoleId(any()))
                .thenReturn(allPermissionCodes());
    }

    @Test
    void shouldCreateKnowledgeBaseWithValidRequest() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(knowledgeBaseService.createKnowledgeBase(eq(7L), eq(10L),
                any(KnowledgeBaseCreateRequest.class))).thenReturn(knowledgeBase());

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "企业手册",
                                  "description": "团队知识沉淀",
                                  "accessMode": "PRIVATE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(500))
                .andExpect(jsonPath("$.data.enterpriseId").value(10))
                .andExpect(jsonPath("$.data.name").value("企业手册"))
                .andExpect(jsonPath("$.data.description").value("团队知识沉淀"))
                .andExpect(jsonPath("$.data.accessMode").value("PRIVATE"))
                .andExpect(jsonPath("$.data.ownerUserId").value(7))
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-17T03:00:00.123Z"));

        verify(knowledgeBaseService).createKnowledgeBase(eq(7L), eq(10L),
                any(KnowledgeBaseCreateRequest.class));
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/enterprises/10/knowledge-bases")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "企业手册"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldRejectMissingEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "企业手册"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldRejectNonMember() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        // 覆盖共享桩：EnterpriseContextFilter 查询不到该企业成员 → 403。
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "企业手册"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldRejectBlankName() throws Exception {
        assertInvalidRequest("""
                {"name": "   "}
                """);
    }

    @Test
    void shouldRejectTooLongName() throws Exception {
        // name 超过 100 字符 → @Size 校验失败 → 400。
        assertInvalidRequest("""
                {"name": "%s"}
                """.formatted("很".repeat(101)));
    }

    @Test
    void shouldRejectWhenServiceThrowsForbidden() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(knowledgeBaseService).createKnowledgeBase(eq(7L), eq(10L),
                        any(KnowledgeBaseCreateRequest.class));

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "企业手册"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ==================== 列表与详情查询 ====================

    @Test
    void shouldListVisibleKnowledgeBases() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        // 一个 PUBLIC 非成员（myRole=null）+ 一个 PRIVATE 成员（myRole=ADMIN）。
        when(knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L))
                .thenReturn(List.of(
                        knowledgeBaseVO(101L, "公开手册", KnowledgeBaseAccessMode.PUBLIC, null),
                        knowledgeBaseVO(102L, "私有手册", KnowledgeBaseAccessMode.PRIVATE,
                                KnowledgeBaseMemberRole.ADMIN)));

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(101))
                .andExpect(jsonPath("$.data[0].name").value("公开手册"))
                .andExpect(jsonPath("$.data[0].accessMode").value("PUBLIC"))
                .andExpect(jsonPath("$.data[0].myRole").doesNotExist())
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-08-17T03:00:00.123Z"))
                .andExpect(jsonPath("$.data[1].id").value(102))
                .andExpect(jsonPath("$.data[1].accessMode").value("PRIVATE"))
                .andExpect(jsonPath("$.data[1].myRole").value("ADMIN"));

        verify(knowledgeBaseService).listVisibleKnowledgeBases(7L, 10L);
    }

    @Test
    void shouldGetKnowledgeBaseDetail() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(knowledgeBaseService.getKnowledgeBase(7L, 10L, 102L))
                .thenReturn(knowledgeBaseVO(102L, "私有手册", KnowledgeBaseAccessMode.PRIVATE,
                        KnowledgeBaseMemberRole.EDITOR));

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/102")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(102))
                .andExpect(jsonPath("$.data.name").value("私有手册"))
                .andExpect(jsonPath("$.data.accessMode").value("PRIVATE"))
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.myRole").value("EDITOR"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-17T03:00:00.123Z"));

        verify(knowledgeBaseService).getKnowledgeBase(7L, 10L, 102L);
    }

    @Test
    void shouldReturnNotFoundForPrivateNonMemberDetail() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(knowledgeBaseService.getKnowledgeBase(7L, 10L, 102L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/102")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnUnauthorizedForDetailWithoutToken() throws Exception {
        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/102")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldRejectDetailMissingEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/102")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldRejectDetailForNonMember() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        // 覆盖共享桩：EnterpriseContextFilter 查询不到该企业成员 → 403。
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(get("/api/enterprises/10/knowledge-bases/102")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(knowledgeBaseService);
    }

    // ==================== 管理：成员管理 / 更新 / 删除 / 状态 ====================

    @Test
    void shouldAddKnowledgeBaseMember() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(knowledgeBaseService.addKnowledgeBaseMember(eq(7L), eq(10L), eq(100L),
                any(KnowledgeBaseMemberAddRequest.class))).thenReturn(knowledgeBaseMemberVO(8L, "EDITOR"));

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases/100/members")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 8, "memberRole": "EDITOR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.knowledgeBaseId").value(100))
                .andExpect(jsonPath("$.data.userId").value(8))
                .andExpect(jsonPath("$.data.memberRole").value("EDITOR"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-17T03:00:00.123Z"));

        verify(knowledgeBaseService).addKnowledgeBaseMember(eq(7L), eq(10L), eq(100L),
                any(KnowledgeBaseMemberAddRequest.class));
    }

    @Test
    void shouldRejectDuplicateMemberWhenAdding() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_MEMBER_ALREADY_EXISTS))
                .when(knowledgeBaseService).addKnowledgeBaseMember(eq(7L), eq(10L), eq(100L),
                        any(KnowledgeBaseMemberAddRequest.class));

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases/100/members")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 8, "memberRole": "VIEWER"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_MEMBER_ALREADY_EXISTS"));
    }

    @Test
    void shouldRejectMissingMemberRoleWhenAdding() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases/100/members")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 8}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldUpdateKnowledgeBaseMemberRole() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(knowledgeBaseService.updateKnowledgeBaseMemberRole(eq(7L), eq(10L), eq(100L), eq(8L),
                any(KnowledgeBaseMemberRoleUpdateRequest.class))).thenReturn(knowledgeBaseMemberVO(8L, "ADMIN"));

        mockMvc.perform(put("/api/enterprises/10/knowledge-bases/100/members/8/role")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberRole": "ADMIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.memberRole").value("ADMIN"));

        verify(knowledgeBaseService).updateKnowledgeBaseMemberRole(eq(7L), eq(10L), eq(100L), eq(8L),
                any(KnowledgeBaseMemberRoleUpdateRequest.class));
    }

    @Test
    void shouldRejectMissingRoleWhenUpdatingMemberRole() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(put("/api/enterprises/10/knowledge-bases/100/members/8/role")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberRole": null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldRemoveKnowledgeBaseMember() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(delete("/api/enterprises/10/knowledge-bases/100/members/8")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(knowledgeBaseService).removeKnowledgeBaseMember(7L, 10L, 100L, 8L);
    }

    @Test
    void shouldUpdateKnowledgeBase() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(knowledgeBaseService.updateKnowledgeBase(eq(7L), eq(10L), eq(100L),
                any(KnowledgeBaseUpdateRequest.class))).thenReturn(knowledgeBaseVO(100L, "新手册",
                KnowledgeBaseAccessMode.PUBLIC, null));

        mockMvc.perform(put("/api/enterprises/10/knowledge-bases/100")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "新手册",
                                  "description": "新描述",
                                  "accessMode": "PUBLIC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("新手册"))
                .andExpect(jsonPath("$.data.accessMode").value("PUBLIC"));

        verify(knowledgeBaseService).updateKnowledgeBase(eq(7L), eq(10L), eq(100L),
                any(KnowledgeBaseUpdateRequest.class));
    }

    @Test
    void shouldRejectMissingNameWhenUpdating() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(put("/api/enterprises/10/knowledge-bases/100")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "新描述", "accessMode": "PUBLIC"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldRejectMissingAccessModeWhenUpdating() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(put("/api/enterprises/10/knowledge-bases/100")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "新手册", "description": "新描述"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldDeleteKnowledgeBase() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(delete("/api/enterprises/10/knowledge-bases/100")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(knowledgeBaseService).deleteKnowledgeBase(7L, 10L, 100L);
    }

    @Test
    void shouldUpdateKnowledgeBaseStatus() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(knowledgeBaseService.updateKnowledgeBaseStatus(eq(7L), eq(10L), eq(100L),
                any(KnowledgeBaseStatusUpdateRequest.class))).thenReturn(knowledgeBaseVO(100L, "手册",
                KnowledgeBaseAccessMode.PRIVATE, null));

        mockMvc.perform(put("/api/enterprises/10/knowledge-bases/100/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(knowledgeBaseService).updateKnowledgeBaseStatus(eq(7L), eq(10L), eq(100L),
                any(KnowledgeBaseStatusUpdateRequest.class));
    }

    @Test
    void shouldRejectMissingStatusWhenUpdating() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(put("/api/enterprises/10/knowledge-bases/100/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldReturnForbiddenWhenCreatingMemberWithoutManagerPermission() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(knowledgeBaseService).addKnowledgeBaseMember(eq(7L), eq(10L), eq(100L),
                        any(KnowledgeBaseMemberAddRequest.class));

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases/100/members")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 8, "memberRole": "VIEWER"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnUnauthorizedForManagementWithoutToken() throws Exception {
        mockMvc.perform(delete("/api/enterprises/10/knowledge-bases/100")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void shouldRejectManagementMissingEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(delete("/api/enterprises/10/knowledge-bases/100")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(knowledgeBaseService);
    }

    private void assertInvalidRequest(String content) throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/enterprises/10/knowledge-bases")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(knowledgeBaseService);
    }

    private KnowledgeBase knowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(500L);
        knowledgeBase.setEnterpriseId(10L);
        knowledgeBase.setName("企业手册");
        knowledgeBase.setDescription("团队知识沉淀");
        knowledgeBase.setAccessMode(KnowledgeBaseAccessMode.PRIVATE);
        knowledgeBase.setOwnerUserId(7L);
        knowledgeBase.setStatus(KnowledgeBaseStatus.NORMAL);
        // 验证 API 时间序列化统一收敛到毫秒精度并保持 UTC。
        knowledgeBase.setCreatedAt(Instant.parse("2026-08-17T03:00:00.123987654Z"));
        knowledgeBase.setUpdatedAt(Instant.parse("2026-08-17T03:00:00.123987654Z"));
        return knowledgeBase;
    }

    /** 构造知识库成员 VO（供成员管理接口桩返回），时间固定为毫秒精度的 UTC 字符串。 */
    private KnowledgeBaseMemberVO knowledgeBaseMemberVO(Long userId, String memberRole) {
        KnowledgeBaseMember member = new KnowledgeBaseMember();
        member.setId(300L);
        member.setKnowledgeBaseId(100L);
        member.setEnterpriseId(10L);
        member.setUserId(userId);
        member.setMemberRole(KnowledgeBaseMemberRole.valueOf(memberRole));
        member.setCreatedAt(Instant.parse("2026-08-17T03:00:00.123987654Z"));
        member.setUpdatedAt(Instant.parse("2026-08-17T03:00:00.123987654Z"));
        return KnowledgeBaseMemberVO.from(member);
    }

    /** 构造知识库 VO（供列表/详情查询接口桩返回），时间固定为毫秒精度的 UTC 字符串。 */
    private KnowledgeBaseVO knowledgeBaseVO(Long id, String name, KnowledgeBaseAccessMode accessMode,
                                            KnowledgeBaseMemberRole myRole) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setEnterpriseId(10L);
        knowledgeBase.setName(name);
        knowledgeBase.setAccessMode(accessMode);
        knowledgeBase.setOwnerUserId(7L);
        knowledgeBase.setStatus(KnowledgeBaseStatus.NORMAL);
        knowledgeBase.setCreatedAt(Instant.parse("2026-08-17T03:00:00.123987654Z"));
        knowledgeBase.setUpdatedAt(Instant.parse("2026-08-17T03:00:00.123987654Z"));
        return KnowledgeBaseVO.from(knowledgeBase, myRole);
    }

    /** V6 预置的全部企业权限码：作为共享桩返回，保持过滤器权限加载走通。 */
    private List<String> allPermissionCodes() {
        return List.of(
                "enterprise:update", "member:view", "member:remove", "member:status",
                "invitation:create", "invitation:list", "invitation:revoke",
                "department:create", "department:update", "department:delete", "department:status");
    }

    /** 构造企业角色记录（供 EnterpriseContextFilter 按 roleId 解析角色编码）。 */
    private EnterpriseRole role(String code) {
        EnterpriseRole role = new EnterpriseRole();
        role.setId(100L);
        role.setEnterpriseId(10L);
        role.setCode(code);
        role.setName(code);
        role.setStatus(EnterpriseRoleStatus.NORMAL);
        return role;
    }
}
