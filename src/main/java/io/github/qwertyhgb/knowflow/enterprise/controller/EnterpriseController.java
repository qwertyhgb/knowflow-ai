package io.github.qwertyhgb.knowflow.enterprise.controller;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.audit.annotation.OperationLog;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseMemberStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseService;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseMemberVO;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 企业接口。
 *
 * <p>当前接口要求登录（{@code SecurityConfig} 中 {@code /api/enterprises/**}
 * 不在放行名单内），当前用户 ID 从 {@link Authentication} 主身份中获取。</p>
 *
 * <p><strong>企业上下文规则：</strong>除「创建企业」「我的企业列表」外，
 * 本控制器的接口都位于企业作用域路径，必须携带 {@code X-Enterprise-Id}
 * 请求头且与路径企业 ID 一致——由 {@code EnterpriseContextFilter}
 * 在进入本控制器前统一校验并注入主体，缺失或不一致时分别返回 400。</p>
 */
@RestController
@RequestMapping("/api/enterprises")
public class EnterpriseController {

    private final EnterpriseService enterpriseService;

    public EnterpriseController(EnterpriseService enterpriseService) {
        this.enterpriseService = enterpriseService;
    }

    /**
     * 创建企业：当前用户自动成为该企业的所有者成员。
     */
    @PostMapping
    @OperationLog("创建企业")
    public Result<EnterpriseVO> createEnterprise(Authentication authentication,
                                                 @Valid @RequestBody EnterpriseCreateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseService.createEnterprise(userId, request));
    }

    /**
     * 查询当前用户加入的全部正常企业；未加入任何企业时返回空列表 {@code []}。
     */
    @GetMapping
    public Result<List<EnterpriseVO>> listMyEnterprises(Authentication authentication) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseService.listMyEnterprises(userId));
    }

    /**
     * 查询企业详情。
     *
     * <p>仅允许该企业的正常成员访问：企业不存在返回 404，
     * 非成员或成员被禁用返回 403（知道 {@code enterpriseId} 不等于有权访问）。</p>
     */
    @GetMapping("/{enterpriseId}")
    public Result<EnterpriseVO> getEnterpriseDetail(Authentication authentication,
                                                    @PathVariable Long enterpriseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseService.getEnterpriseDetail(userId, enterpriseId));
    }

    /**
     * 更新企业名称。
     *
     * <p>需要 {@code enterprise:update} 权限。该权限码由 {@code EnterpriseContextFilter}
     * 从当前成员角色的角色-权限关联加载并注入 {@code Authentication} 的 authorities，
     * 权限不足时在方法执行前抛 {@code AccessDeniedException} 并返回 403。</p>
     */
    @PutMapping("/{enterpriseId}")
    @PreAuthorize("hasAuthority('enterprise:update')")
    public Result<EnterpriseVO> updateEnterprise(Authentication authentication,
                                                 @PathVariable Long enterpriseId,
                                                 @Valid @RequestBody EnterpriseUpdateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseService.updateEnterprise(userId, enterpriseId, request));
    }

    /**
     * 查询企业成员列表。
     *
     * <p>需要 {@code member:view} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @GetMapping("/{enterpriseId}/members")
    @PreAuthorize("hasAuthority('member:view')")
    public Result<List<EnterpriseMemberVO>> listMembers(Authentication authentication,
                                                        @PathVariable Long enterpriseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseService.listMembers(userId, enterpriseId));
    }

    /**
     * 移除企业成员：将指定用户的成员关系状态置为禁用（软删除）。
     *
     * <p>仅允许该企业 OWNER 或 ADMIN 操作——MEMBER 无权；
     * 管理员不能移除自己，也不能移除 OWNER。</p>
     *
     * <p>需要 {@code member:remove} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @PostMapping("/{enterpriseId}/members/{targetUserId}/remove")
    @PreAuthorize("hasAuthority('member:remove')")
    public Result<Void> removeMember(Authentication authentication,
                                     @PathVariable Long enterpriseId,
                                     @PathVariable Long targetUserId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        enterpriseService.removeMember(userId, enterpriseId, targetUserId);
        return Result.success(null);
    }

    /**
     * 当前成员主动退出企业。
     *
     * <p>退出目标始终是当前登录用户，不接收 {@code targetUserId}，
     * 与管理员移除其他成员的接口保持明确区分。</p>
     */
    @PostMapping("/{enterpriseId}/members/leave")
    public Result<Void> leaveEnterprise(Authentication authentication,
                                        @PathVariable Long enterpriseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        enterpriseService.leaveEnterprise(userId, enterpriseId);
        return Result.success(null);
    }

    /**
     * 修改企业成员状态：启用（{@code NORMAL}）或禁用（{@code DISABLED}）目标成员。
     *
     * <p>仅允许该企业 OWNER 或 ADMIN 操作——MEMBER 无权；
     * 管理员不能修改自己的状态，也不能修改 OWNER 的状态。
     * 请求状态与当前状态相同时幂等返回，不触发更新。</p>
     *
     * <p>需要 {@code member:status} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @PutMapping("/{enterpriseId}/members/{targetUserId}/status")
    @PreAuthorize("hasAuthority('member:status')")
    public Result<EnterpriseMemberVO> updateMemberStatus(Authentication authentication,
                                                         @PathVariable Long enterpriseId,
                                                         @PathVariable Long targetUserId,
                                                         @Valid @RequestBody EnterpriseMemberStatusUpdateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(
                enterpriseService.updateMemberStatus(userId, enterpriseId, targetUserId, request));
    }
}
