package io.github.qwertyhgb.knowflow.enterprise.controller;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseInvitationCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseInvitationService;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseInvitationVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 企业成员邀请接口（企业视角的邀请管理）。
 *
 * <p>与 {@link MyInvitationController}（被邀请人视角，挂在 {@code /api/invitations} 下）相对，
 * 本控制器承载企业视角的操作：创建邀请、邀请列表、撤销邀请，
 * 均要求当前用户是该企业的管理成员（OWNER/ADMIN）。</p>
 *
 * <p>当前接口要求登录（{@code SecurityConfig} 中除注册/登录与 Swagger 外
 * 的请求均需认证），当前用户 ID 从 {@link Authentication} 主身份中获取。</p>
 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/invitations")
public class EnterpriseInvitationController {

    private final EnterpriseInvitationService enterpriseInvitationService;

    public EnterpriseInvitationController(EnterpriseInvitationService enterpriseInvitationService) {
        this.enterpriseInvitationService = enterpriseInvitationService;
    }

    /**
     * 创建成员邀请：向指定邮箱发出加入企业的邀请（需企业 OWNER 或 ADMIN 角色）。
     *
     * <p>响应中的 {@code token} 是明文邀请令牌，<strong>仅此一次返回</strong>，
     * 系统只保存其哈希；邀请人需自行把它转交给被邀请人（当前阶段不发送邮件）。</p>
     *
     * <p>需要 {@code invitation:create} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @PostMapping
    @PreAuthorize("hasAuthority('invitation:create')")
    public Result<EnterpriseInvitationVO> createInvitation(Authentication authentication,
                                                           @PathVariable Long enterpriseId,
                                                           @Valid @RequestBody EnterpriseInvitationCreateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseInvitationService.createInvitation(userId, enterpriseId, request));
    }

    /**
     * 查询企业邀请列表（需企业 OWNER 或 ADMIN 角色）。
     *
     * <p>按创建时间倒序返回全部邀请记录（含被邀请邮箱、授予角色、状态、过期时间），
     * 暂不分页；不返回明文令牌。</p>
     *
     * <p>需要 {@code invitation:list} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('invitation:list')")
    public Result<List<EnterpriseInvitationVO>> listInvitations(Authentication authentication,
                                                                @PathVariable Long enterpriseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseInvitationService.listEnterpriseInvitations(userId, enterpriseId));
    }

    /**
     * 撤销邀请：把 PENDING 邀请流转为 REVOKED（需企业 OWNER 或 ADMIN 角色）。
     *
     * <p>撤销后被邀请人不能再凭原令牌接受邀请；撤销不可逆，
     * 需要重新邀请时创建一条新的邀请记录即可。</p>
     *
     * <p>需要 {@code invitation:revoke} 权限，由 {@code EnterpriseContextFilter} 从
     * 角色-权限关联加载权限码并注入 authorities；权限不足返回 403。</p>
     */
    @PostMapping("/{invitationId}/revoke")
    @PreAuthorize("hasAuthority('invitation:revoke')")
    public Result<EnterpriseInvitationVO> revokeInvitation(Authentication authentication,
                                                           @PathVariable Long enterpriseId,
                                                           @PathVariable Long invitationId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseInvitationService.revokeInvitation(userId, enterpriseId, invitationId));
    }
}
