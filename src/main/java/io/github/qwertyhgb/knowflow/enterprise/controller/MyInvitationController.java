package io.github.qwertyhgb.knowflow.enterprise.controller;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.InvitationAcceptRequest;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseInvitationService;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseInvitationVO;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 我的邀请接口（被邀请人视角）。
 *
 * <p>与 {@link EnterpriseInvitationController}（企业视角的邀请管理，挂在
 * {@code /api/enterprises/{enterpriseId}/invitations} 下）相对，
 * 本控制器承载被邀请人视角的操作：「我的待处理邀请」列表与接受邀请。</p>
 *
 * <p>接口要求登录（{@code SecurityConfig} 中除注册/登录与 Swagger 外的请求均需认证），
 * 当前用户 ID 从 {@link Authentication} 主身份中获取。</p>
 */
@RestController
@RequestMapping("/api/invitations")
public class MyInvitationController {

    private final EnterpriseInvitationService enterpriseInvitationService;

    public MyInvitationController(EnterpriseInvitationService enterpriseInvitationService) {
        this.enterpriseInvitationService = enterpriseInvitationService;
    }

    /**
     * 查询当前登录邮箱收到的待处理邀请（登录即可，无需是企业成员）。
     *
     * <p>仅返回「PENDING 且未过期」的邀请，按过期时间升序（最先过期的排最前），
     * 并附带企业名称；顺带把该邮箱已过期的历史邀请批量置为 EXPIRED。
     * 令牌不在此返回（仅在创建邀请时返回一次）。</p>
     */
    @GetMapping("/my")
    public Result<List<EnterpriseInvitationVO>> listMyPendingInvitations(Authentication authentication) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseInvitationService.listMyPendingInvitations(userId));
    }

    /**
     * 接受邀请：被邀请人凭创建邀请时返回的一次性令牌，把登录账号加入企业。
     *
     * <p><strong>令牌放在请求体而非 URL 路径：</strong>URL 会出现在访问日志与
     * 代理服务器日志中，令牌属于凭证，与密码同等对待，只经请求体传输。
     * 响应中的邀请视图不再包含 {@code token}（令牌仅创建时返回一次）。</p>
     */
    @PostMapping("/accept")
    public Result<EnterpriseInvitationVO> acceptInvitation(Authentication authentication,
                                                           @Valid @RequestBody InvitationAcceptRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(enterpriseInvitationService.acceptInvitation(userId, request));
    }
}
