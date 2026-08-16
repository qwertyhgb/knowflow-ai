package io.github.qwertyhgb.knowflow.enterprise.controller;

import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseDepartment;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseDepartmentStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.DepartmentService;
import io.github.qwertyhgb.knowflow.enterprise.vo.DepartmentTreeVO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 创建部门接口的 Web 层测试：认证、企业上下文、参数校验、时间格式和统一响应结构。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DepartmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepartmentService departmentService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @BeforeEach
    void stubEnterpriseContext() {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(10L);
        member.setUserId(7L);
        member.setMemberRole(EnterpriseMemberRole.OWNER);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);
    }

    @Test
    void shouldCreateDepartmentWithValidRequest() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(departmentService.createDepartment(eq(7L), eq(10L), any(DepartmentCreateRequest.class)))
                .thenReturn(department());

        mockMvc.perform(post("/api/enterprises/10/departments")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "后端组",
                                  "parentId": 20,
                                  "sortOrder": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.enterpriseId").value(10))
                .andExpect(jsonPath("$.data.parentId").value(20))
                .andExpect(jsonPath("$.data.name").value("后端组"))
                .andExpect(jsonPath("$.data.sortOrder").value(5))
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-16T01:00:00.123Z"));

        verify(departmentService).createDepartment(eq(7L), eq(10L), any(DepartmentCreateRequest.class));
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/enterprises/10/departments")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "研发部"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(departmentService);
    }

    @Test
    void shouldRejectMissingEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/enterprises/10/departments")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "研发部"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(departmentService);
    }

    @Test
    void shouldRejectMismatchedEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/enterprises/10/departments")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "研发部"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISMATCH"));

        verifyNoInteractions(departmentService);
    }

    @Test
    void shouldRejectBlankName() throws Exception {
        assertInvalidRequest("""
                {"name": "   "}
                """);
    }

    @Test
    void shouldRejectNonPositiveParentId() throws Exception {
        assertInvalidRequest("""
                {"name": "研发部", "parentId": 0}
                """);
    }

    @Test
    void shouldRejectNegativeSortOrder() throws Exception {
        assertInvalidRequest("""
                {"name": "研发部", "sortOrder": -1}
                """);
    }

    @Test
    void shouldReturnConflictForDuplicateSiblingName() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(departmentService.createDepartment(eq(7L), eq(10L), any(DepartmentCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.DEPARTMENT_NAME_ALREADY_EXISTS));

        mockMvc.perform(post("/api/enterprises/10/departments")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "研发部"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTMENT_NAME_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("同级部门名称已存在"));
    }

    @Test
    void shouldListDepartmentTreeWithValidContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(departmentService.listDepartmentTree(7L, 10L)).thenReturn(departmentTree());

        mockMvc.perform(get("/api/enterprises/10/departments")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("产品部"))
                .andExpect(jsonPath("$.data[0].children[0].id").value(2))
                .andExpect(jsonPath("$.data[0].children[0].name").value("后端组"))
                .andExpect(jsonPath("$.data[0].children[0].children").isArray())
                .andExpect(jsonPath("$.data[0].children[0].children").isEmpty());

        verify(departmentService).listDepartmentTree(7L, 10L);
    }

    @Test
    void shouldReturnEmptyDepartmentTree() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        when(departmentService.listDepartmentTree(7L, 10L)).thenReturn(List.of());

        mockMvc.perform(get("/api/enterprises/10/departments")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void shouldRejectDepartmentTreeWithoutEnterpriseContext() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(get("/api/enterprises/10/departments")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_CONTEXT_MISSING"));

        verifyNoInteractions(departmentService);
    }

    @Test
    void shouldUpdateDepartmentWithValidRequest() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        EnterpriseDepartment updated = department();
        updated.setName("平台研发部");
        updated.setParentId(null);
        updated.setSortOrder(8);
        when(departmentService.updateDepartment(eq(7L), eq(10L), eq(100L),
                any(DepartmentUpdateRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/enterprises/10/departments/100")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "平台研发部",
                                  "parentId": null,
                                  "sortOrder": 8
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.name").value("平台研发部"))
                .andExpect(jsonPath("$.data.parentId").doesNotExist())
                .andExpect(jsonPath("$.data.sortOrder").value(8));

        verify(departmentService).updateDepartment(eq(7L), eq(10L), eq(100L),
                any(DepartmentUpdateRequest.class));
    }

    @Test
    void shouldRejectInvalidUpdateRequest() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(put("/api/enterprises/10/departments/100")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "   ", "sortOrder": -1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(departmentService);
    }

    @Test
    void shouldDisableDepartmentWithValidRequest() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        EnterpriseDepartment updated = department();
        updated.setStatus(EnterpriseDepartmentStatus.DISABLED);
        when(departmentService.updateDepartmentStatus(eq(7L), eq(10L), eq(100L),
                any(DepartmentStatusUpdateRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/enterprises/10/departments/100/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        verify(departmentService).updateDepartmentStatus(eq(7L), eq(10L), eq(100L),
                any(DepartmentStatusUpdateRequest.class));
    }

    @Test
    void shouldRejectMissingDepartmentStatus() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(put("/api/enterprises/10/departments/100/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(departmentService);
    }

    @Test
    void shouldRejectUnknownDepartmentStatus() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(put("/api/enterprises/10/departments/100/status")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "FROZEN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(departmentService);
    }

    @Test
    void shouldDeleteLeafDepartment() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(delete("/api/enterprises/10/departments/100")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(departmentService).deleteDepartment(7L, 10L, 100L);
    }

    @Test
    void shouldReturnConflictWhenDeletingDepartmentWithChildren() throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(ErrorCode.DEPARTMENT_HAS_CHILDREN))
                .when(departmentService).deleteDepartment(7L, 10L, 100L);

        mockMvc.perform(delete("/api/enterprises/10/departments/100")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTMENT_HAS_CHILDREN"))
                .andExpect(jsonPath("$.message").value("请先移动或删除子部门"));
    }

    private void assertInvalidRequest(String content) throws Exception {
        when(tokenService.resolveUserId("valid-token")).thenReturn(Optional.of(7L));

        mockMvc.perform(post("/api/enterprises/10/departments")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Enterprise-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(departmentService);
    }

    private EnterpriseDepartment department() {
        EnterpriseDepartment department = new EnterpriseDepartment();
        department.setId(100L);
        department.setEnterpriseId(10L);
        department.setParentId(20L);
        department.setName("后端组");
        department.setSortOrder(5);
        department.setStatus(EnterpriseDepartmentStatus.NORMAL);
        // 验证 API 时间序列化统一收敛到毫秒精度并保持 UTC。
        department.setCreatedAt(Instant.parse("2026-08-16T01:00:00.123987654Z"));
        department.setUpdatedAt(Instant.parse("2026-08-16T01:00:00.123987654Z"));
        return department;
    }

    private List<DepartmentTreeVO> departmentTree() {
        EnterpriseDepartment child = new EnterpriseDepartment();
        child.setId(2L);
        child.setEnterpriseId(10L);
        child.setParentId(1L);
        child.setName("后端组");
        child.setSortOrder(0);
        child.setStatus(EnterpriseDepartmentStatus.NORMAL);

        EnterpriseDepartment root = new EnterpriseDepartment();
        root.setId(1L);
        root.setEnterpriseId(10L);
        root.setName("产品部");
        root.setSortOrder(0);
        root.setStatus(EnterpriseDepartmentStatus.NORMAL);

        DepartmentTreeVO childNode = DepartmentTreeVO.from(child, List.of());
        return List.of(DepartmentTreeVO.from(root, List.of(childNode)));
    }
}
