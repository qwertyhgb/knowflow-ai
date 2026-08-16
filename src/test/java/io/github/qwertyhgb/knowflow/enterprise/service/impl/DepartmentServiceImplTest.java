package io.github.qwertyhgb.knowflow.enterprise.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseDepartment;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseDepartmentStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseDepartmentMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseMembershipChecker;
import io.github.qwertyhgb.knowflow.enterprise.vo.DepartmentTreeVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepartmentServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-16T01:00:00Z");

    @Mock
    private EnterpriseMapper enterpriseMapper;

    @Mock
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @Mock
    private EnterpriseDepartmentMapper enterpriseDepartmentMapper;

    private DepartmentServiceImpl departmentService;

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, EnterpriseMember.class);
        TableInfoHelper.initTableInfo(assistant, EnterpriseDepartment.class);
    }

    @BeforeEach
    void setUp() {
        // 使用真实 checker（内部走 enterpriseMapper / enterpriseMemberMapper 两个 mock），
        // 保持既有 exists / selectOne 桩的语义不变。
        EnterpriseMembershipChecker membershipChecker =
                new EnterpriseMembershipChecker(enterpriseMapper, enterpriseMemberMapper);
        departmentService = new DepartmentServiceImpl(
                enterpriseDepartmentMapper,
                membershipChecker,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldCreateRootDepartmentAsOwner() {
        allowActiveMember();
        when(enterpriseDepartmentMapper.insert(any(EnterpriseDepartment.class))).thenAnswer(invocation -> {
            EnterpriseDepartment department = invocation.getArgument(0);
            department.setId(100L);
            return 1;
        });

        DepartmentCreateRequest request = request("  产品研发部  ", null, null);
        EnterpriseDepartment result = departmentService.createDepartment(7L, 10L, request);

        ArgumentCaptor<EnterpriseDepartment> captor = ArgumentCaptor.forClass(EnterpriseDepartment.class);
        verify(enterpriseDepartmentMapper).insert(captor.capture());
        EnterpriseDepartment saved = captor.getValue();
        assertEquals(10L, saved.getEnterpriseId());
        assertNull(saved.getParentId());
        assertEquals("产品研发部", saved.getName());
        assertEquals(0, saved.getSortOrder());
        assertEquals(EnterpriseDepartmentStatus.NORMAL, saved.getStatus());
        assertEquals(NOW, saved.getCreatedAt());
        assertEquals(NOW, saved.getUpdatedAt());
        assertSame(saved, result);
        assertEquals(100L, result.getId());
    }

    @Test
    void shouldCreateChildDepartmentAsAdmin() {
        allowActiveMember();
        EnterpriseDepartment parent = department(20L, 10L, null, "研发部");
        when(enterpriseDepartmentMapper.selectOne(any())).thenReturn(parent);

        EnterpriseDepartment result = departmentService.createDepartment(
                7L, 10L, request("后端组", 20L, 5));

        assertEquals(20L, result.getParentId());
        assertEquals(5, result.getSortOrder());
        verify(enterpriseDepartmentMapper).insert(result);
    }

    @Test
    void shouldRejectMissingEnterpriseBeforePermissionCheck() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> departmentService.createDepartment(7L, 99L, request("研发部", null, 0)));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(enterpriseMemberMapper, never()).exists(any());
        verify(enterpriseDepartmentMapper, never()).insert(any(EnterpriseDepartment.class));
    }

    @Test
    void shouldAllowPlainMemberToCreateDepartment() {
        // MEMBER 角色也能创建部门：管理权限由 Controller 的 @PreAuthorize 校验，
        // Service 只保证成员身份，不再按角色拒绝。
        allowActiveMember();
        when(enterpriseDepartmentMapper.insert(any(EnterpriseDepartment.class))).thenAnswer(invocation -> {
            EnterpriseDepartment department = invocation.getArgument(0);
            department.setId(100L);
            return 1;
        });

        EnterpriseDepartment result = departmentService.createDepartment(7L, 10L, request("研发部", null, 0));

        assertEquals(100L, result.getId(), "正常成员（含 MEMBER）应可创建部门");
        verify(enterpriseDepartmentMapper).insert(any(EnterpriseDepartment.class));
    }

    @Test
    void shouldRejectParentOutsideCurrentEnterprise() {
        allowActiveMember();
        // 按「父部门 ID + 当前企业 ID」查询不到时，不允许跨企业挂接。
        when(enterpriseDepartmentMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> departmentService.createDepartment(7L, 10L, request("后端组", 30L, 0)));

        assertEquals(ErrorCode.DEPARTMENT_PARENT_NOT_FOUND, exception.getErrorCode());
        verify(enterpriseDepartmentMapper, never()).insert(any(EnterpriseDepartment.class));
    }

    @Test
    void shouldRejectDuplicateNameUnderSameParent() {
        allowActiveMember();
        when(enterpriseDepartmentMapper.exists(any())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> departmentService.createDepartment(7L, 10L, request("研发部", null, 0)));

        assertEquals(ErrorCode.DEPARTMENT_NAME_ALREADY_EXISTS, exception.getErrorCode());
        verify(enterpriseDepartmentMapper, never()).insert(any(EnterpriseDepartment.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<EnterpriseDepartment>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(enterpriseDepartmentMapper).exists(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("enterprise_id"));
        assertTrue(sql.contains("name"));
        assertTrue(sql.contains("parent_id") && sql.contains("IS NULL"));
    }

    @Test
    void shouldBuildDepartmentTreeForActiveMember() {
        allowActiveMember();
        EnterpriseDepartment product = department(1L, 10L, null, "产品部");
        product.setSortOrder(0);
        EnterpriseDepartment api = department(3L, 10L, 2L, "API组");
        api.setSortOrder(1);
        EnterpriseDepartment backend = department(2L, 10L, 1L, "后端组");
        backend.setSortOrder(5);
        EnterpriseDepartment hr = department(4L, 10L, null, "人事部");
        hr.setSortOrder(10);
        // 模拟数据库按 sortOrder、id 排序后的扁平结果。
        when(enterpriseDepartmentMapper.selectList(any()))
                .thenReturn(List.of(product, api, backend, hr));

        List<DepartmentTreeVO> result = departmentService.listDepartmentTree(7L, 10L);

        assertEquals(List.of(1L, 4L), result.stream().map(DepartmentTreeVO::getId).toList());
        assertEquals(1, result.get(0).getChildren().size());
        assertEquals(2L, result.get(0).getChildren().get(0).getId());
        assertEquals(3L, result.get(0).getChildren().get(0).getChildren().get(0).getId());
        assertTrue(result.get(1).getChildren().isEmpty(), "叶子部门 children 应为空列表");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<EnterpriseDepartment>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(enterpriseDepartmentMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("enterprise_id"), "查询必须限定当前企业");
        assertTrue(sql.contains("sort_order") && sql.contains("id"), "查询应使用稳定排序");
    }

    @Test
    void shouldReturnEmptyDepartmentTree() {
        allowActiveMember();
        when(enterpriseDepartmentMapper.selectList(any())).thenReturn(List.of());

        List<DepartmentTreeVO> result = departmentService.listDepartmentTree(7L, 10L);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldRejectDepartmentTreeForInactiveMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> departmentService.listDepartmentTree(7L, 10L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(enterpriseDepartmentMapper, never()).selectList(any());
    }

    @Test
    void shouldRejectDepartmentTreeWhenEnterpriseMissing() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> departmentService.listDepartmentTree(7L, 99L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(enterpriseMemberMapper, never()).exists(any());
        verify(enterpriseDepartmentMapper, never()).selectList(any());
    }

    @Test
    void shouldUpdateDepartmentAndMoveItToRoot() {
        allowActiveMember();
        EnterpriseDepartment root = department(1L, 10L, null, "研发部");
        EnterpriseDepartment target = department(2L, 10L, 1L, "后端组");
        when(enterpriseDepartmentMapper.selectList(any())).thenReturn(List.of(root, target));
        when(enterpriseDepartmentMapper.update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);

        EnterpriseDepartment result = departmentService.updateDepartment(
                7L, 10L, 2L, updateRequest("  平台研发部  ", null, 7));

        assertEquals("平台研发部", result.getName());
        assertNull(result.getParentId(), "parentId=null 应移动为一级部门");
        assertEquals(7, result.getSortOrder());
        assertEquals(NOW, result.getUpdatedAt());
        verify(enterpriseDepartmentMapper).update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectMovingDepartmentUnderItsDescendant() {
        allowActiveMember();
        EnterpriseDepartment root = department(1L, 10L, null, "研发部");
        EnterpriseDepartment child = department(2L, 10L, 1L, "后端组");
        when(enterpriseDepartmentMapper.selectList(any())).thenReturn(List.of(root, child));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> departmentService.updateDepartment(
                        7L, 10L, 1L, updateRequest("研发部", 2L, 0)));

        assertEquals(ErrorCode.DEPARTMENT_PARENT_CYCLE, exception.getErrorCode());
        verify(enterpriseDepartmentMapper, never()).update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectUnknownParentWhenUpdatingDepartment() {
        allowActiveMember();
        EnterpriseDepartment target = department(2L, 10L, null, "后端组");
        when(enterpriseDepartmentMapper.selectList(any())).thenReturn(List.of(target));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> departmentService.updateDepartment(
                        7L, 10L, 2L, updateRequest("后端组", 99L, 0)));

        assertEquals(ErrorCode.DEPARTMENT_PARENT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void shouldDisableDepartment() {
        allowActiveMember();
        EnterpriseDepartment target = department(2L, 10L, 1L, "后端组");
        when(enterpriseDepartmentMapper.selectOne(any())).thenReturn(target);
        when(enterpriseDepartmentMapper.update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                anyDepartmentWrapper())).thenReturn(1);

        EnterpriseDepartment result = departmentService.updateDepartmentStatus(
                7L, 10L, 2L, statusRequest(EnterpriseDepartmentStatus.DISABLED));

        assertSame(target, result);
        assertEquals(EnterpriseDepartmentStatus.DISABLED, result.getStatus());
        assertEquals(NOW, result.getUpdatedAt());
        verify(enterpriseDepartmentMapper).update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                anyDepartmentWrapper());
    }

    @Test
    void shouldEnableDisabledDepartment() {
        allowActiveMember();
        EnterpriseDepartment target = department(2L, 10L, 1L, "后端组");
        target.setStatus(EnterpriseDepartmentStatus.DISABLED);
        when(enterpriseDepartmentMapper.selectOne(any())).thenReturn(target);
        when(enterpriseDepartmentMapper.update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                anyDepartmentWrapper())).thenReturn(1);

        EnterpriseDepartment result = departmentService.updateDepartmentStatus(
                7L, 10L, 2L, statusRequest(EnterpriseDepartmentStatus.NORMAL));

        assertEquals(EnterpriseDepartmentStatus.NORMAL, result.getStatus());
        assertEquals(NOW, result.getUpdatedAt());
    }

    @Test
    void shouldAllowPlainMemberToUpdateDepartmentStatus() {
        // MEMBER 角色也能修改部门状态：管理权限由 @PreAuthorize 校验，Service 只校验成员身份。
        allowActiveMember();
        EnterpriseDepartment target = department(2L, 10L, 1L, "后端组");
        when(enterpriseDepartmentMapper.selectOne(any())).thenReturn(target);
        when(enterpriseDepartmentMapper.update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                anyDepartmentWrapper())).thenReturn(1);

        EnterpriseDepartment result = departmentService.updateDepartmentStatus(
                7L, 10L, 2L, statusRequest(EnterpriseDepartmentStatus.DISABLED));

        assertEquals(EnterpriseDepartmentStatus.DISABLED, result.getStatus(),
                "正常成员（含 MEMBER）应可修改部门状态");
        verify(enterpriseDepartmentMapper).update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                anyDepartmentWrapper());
    }

    @Test
    void shouldTreatUnchangedDepartmentStatusAsIdempotent() {
        allowActiveMember();
        EnterpriseDepartment target = department(2L, 10L, 1L, "后端组");
        target.setStatus(EnterpriseDepartmentStatus.DISABLED);
        Instant originalUpdatedAt = NOW.minusSeconds(60);
        target.setUpdatedAt(originalUpdatedAt);
        when(enterpriseDepartmentMapper.selectOne(any())).thenReturn(target);

        EnterpriseDepartment result = departmentService.updateDepartmentStatus(
                7L, 10L, 2L, statusRequest(EnterpriseDepartmentStatus.DISABLED));

        assertSame(target, result);
        assertEquals(originalUpdatedAt, result.getUpdatedAt());
        verify(enterpriseDepartmentMapper, never()).update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                anyDepartmentWrapper());
    }

    @Test
    void shouldRejectUpdatingStatusOfUnknownDepartment() {
        allowActiveMember();
        when(enterpriseDepartmentMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> departmentService.updateDepartmentStatus(
                        7L, 10L, 99L, statusRequest(EnterpriseDepartmentStatus.NORMAL)));

        assertEquals(ErrorCode.DEPARTMENT_NOT_FOUND, exception.getErrorCode());
        verify(enterpriseDepartmentMapper, never()).update(
                org.mockito.ArgumentMatchers.<EnterpriseDepartment>isNull(),
                anyDepartmentWrapper());
    }

    @Test
    void shouldDeleteLeafDepartment() {
        allowActiveMember();
        EnterpriseDepartment target = department(2L, 10L, 1L, "后端组");
        when(enterpriseDepartmentMapper.selectOne(any())).thenReturn(target);
        when(enterpriseDepartmentMapper.exists(any())).thenReturn(false);
        when(enterpriseDepartmentMapper.delete(anyDepartmentWrapper())).thenReturn(1);

        departmentService.deleteDepartment(7L, 10L, 2L);

        verify(enterpriseDepartmentMapper).delete(anyDepartmentWrapper());
    }

    @Test
    void shouldRejectDeletingDepartmentWithChildren() {
        allowActiveMember();
        EnterpriseDepartment target = department(1L, 10L, null, "研发部");
        when(enterpriseDepartmentMapper.selectOne(any())).thenReturn(target);
        when(enterpriseDepartmentMapper.exists(any())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> departmentService.deleteDepartment(7L, 10L, 1L));

        assertEquals(ErrorCode.DEPARTMENT_HAS_CHILDREN, exception.getErrorCode());
        verify(enterpriseDepartmentMapper, never()).delete(anyDepartmentWrapper());
    }

    private Wrapper<EnterpriseDepartment> anyDepartmentWrapper() {
        return any();
    }

    private void allowActiveMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
    }

    private DepartmentCreateRequest request(String name, Long parentId, Integer sortOrder) {
        DepartmentCreateRequest request = new DepartmentCreateRequest();
        request.setName(name);
        request.setParentId(parentId);
        request.setSortOrder(sortOrder);
        return request;
    }

    private DepartmentUpdateRequest updateRequest(String name, Long parentId, Integer sortOrder) {
        DepartmentUpdateRequest request = new DepartmentUpdateRequest();
        request.setName(name);
        request.setParentId(parentId);
        request.setSortOrder(sortOrder);
        return request;
    }

    private DepartmentStatusUpdateRequest statusRequest(EnterpriseDepartmentStatus status) {
        DepartmentStatusUpdateRequest request = new DepartmentStatusUpdateRequest();
        request.setStatus(status);
        return request;
    }

    private EnterpriseDepartment department(Long id, Long enterpriseId, Long parentId, String name) {
        EnterpriseDepartment department = new EnterpriseDepartment();
        department.setId(id);
        department.setEnterpriseId(enterpriseId);
        department.setParentId(parentId);
        department.setName(name);
        department.setSortOrder(0);
        department.setStatus(EnterpriseDepartmentStatus.NORMAL);
        return department;
    }
}
