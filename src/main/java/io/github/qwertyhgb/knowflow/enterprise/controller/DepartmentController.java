package io.github.qwertyhgb.knowflow.enterprise.controller;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.DepartmentUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseDepartment;
import io.github.qwertyhgb.knowflow.enterprise.service.DepartmentService;
import io.github.qwertyhgb.knowflow.enterprise.vo.DepartmentTreeVO;
import io.github.qwertyhgb.knowflow.enterprise.vo.DepartmentVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;

/**
 * 企业部门接口。
 *
 * <p>接口位于企业作用域路径，必须登录并携带与路径一致的
 * {@code X-Enterprise-Id} 请求头；企业上下文由 {@code EnterpriseContextFilter}
 * 在进入控制器前统一校验。</p>
 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    /**
     * 创建部门。
     *
     * <p>需要 {@code department:create} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @PostMapping
    @PreAuthorize("hasAuthority('department:create')")
    public Result<DepartmentVO> createDepartment(Authentication authentication,
                                                 @PathVariable Long enterpriseId,
                                                 @Valid @RequestBody DepartmentCreateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        EnterpriseDepartment department = departmentService.createDepartment(userId, enterpriseId, request);
        return Result.success(DepartmentVO.from(department));
    }

    /**
     * 查询当前企业的完整部门树（企业正常成员均可访问）。
     */
    @GetMapping
    public Result<List<DepartmentTreeVO>> listDepartmentTree(Authentication authentication,
                                                            @PathVariable Long enterpriseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(departmentService.listDepartmentTree(userId, enterpriseId));
    }

    /**
     * 更新部门名称、层级与排序。
     *
     * <p>需要 {@code department:update} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @PutMapping("/{departmentId}")
    @PreAuthorize("hasAuthority('department:update')")
    public Result<DepartmentVO> updateDepartment(Authentication authentication,
                                                 @PathVariable Long enterpriseId,
                                                 @PathVariable Long departmentId,
                                                 @Valid @RequestBody DepartmentUpdateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        EnterpriseDepartment department = departmentService.updateDepartment(
                userId, enterpriseId, departmentId, request);
        return Result.success(DepartmentVO.from(department));
    }

    /**
     * 启用或禁用部门。
     *
     * <p>需要 {@code department:status} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @PutMapping("/{departmentId}/status")
    @PreAuthorize("hasAuthority('department:status')")
    public Result<DepartmentVO> updateDepartmentStatus(
            Authentication authentication,
            @PathVariable Long enterpriseId,
            @PathVariable Long departmentId,
            @Valid @RequestBody DepartmentStatusUpdateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        EnterpriseDepartment department = departmentService.updateDepartmentStatus(
                userId, enterpriseId, departmentId, request);
        return Result.success(DepartmentVO.from(department));
    }

    /**
     * 删除叶子部门。
     *
     * <p>需要 {@code department:delete} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @DeleteMapping("/{departmentId}")
    @PreAuthorize("hasAuthority('department:delete')")
    public Result<Void> deleteDepartment(Authentication authentication,
                                         @PathVariable Long enterpriseId,
                                         @PathVariable Long departmentId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        departmentService.deleteDepartment(userId, enterpriseId, departmentId);
        return Result.success();
    }
}
