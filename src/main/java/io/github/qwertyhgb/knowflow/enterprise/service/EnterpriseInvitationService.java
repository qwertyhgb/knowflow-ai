package io.github.qwertyhgb.knowflow.enterprise.service;

import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseInvitationCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.InvitationAcceptRequest;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseInvitationVO;

import java.util.List;

/**
 * 企业成员邀请业务服务。
 */
public interface EnterpriseInvitationService {

    /**
     * 创建企业成员邀请：向指定邮箱发出加入企业的邀请，并返回一次性明文邀请令牌。
     *
     * <p><strong>权限（分层）：</strong>仅企业的管理成员可发起邀请——
     * OWNER 可邀请 MEMBER 和 ADMIN，ADMIN 只能邀请 MEMBER；
     * OWNER 角色不通过邀请授予。MEMBER 发起邀请返回 403。</p>
     *
     * <p><strong>校验顺序：</strong>企业不存在 → 404；当前用户非该企业正常成员、
     * 成员被禁用、角色为 MEMBER、试图邀请 OWNER、ADMIN 越权邀请 ADMIN → 403；
     * 邀请邮箱与当前用户邮箱相同（邀请自己）→ 400；
     * 被邀请邮箱对应的已注册用户已存在该企业成员关系（含 DISABLED）→ 409；
     * 该邮箱在同一企业已存在未过期的待接受邀请 → 409。</p>
     *
     * <p><strong>重复邀请规则：</strong>历史邀请（已接受/已撤销/已过期）不阻止新邀请，
     * 每次创建都插入新的 PENDING 记录以保留历史；若发现「仍标记 PENDING 但已超过
     * {@code expires_at}」的旧记录，会先把它惰性更新为 EXPIRED 再创建新邀请。</p>
     *
     * <p><strong>令牌安全：</strong>明文令牌仅在本方法的返回值中出现一次，
     * 数据库只保存其 SHA-256 哈希；令牌丢失只能重新发起邀请。</p>
     *
     * @param userId       当前登录用户 ID（邀请人）
     * @param enterpriseId 目标企业 ID
     * @param request      创建邀请请求（被邀请人邮箱 + 授予角色）
     * @return 创建成功的邀请视图（含一次性明文令牌 {@code token}）
     * @throws io.github.qwertyhgb.knowflow.common.exception.BusinessException
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#NOT_FOUND}（企业不存在）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#FORBIDDEN}（非成员、成员被禁用、角色无权限或越权邀请 ADMIN）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#SELF_INVITATION_NOT_ALLOWED}（邀请自己）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITEE_ALREADY_MEMBER}（被邀请人已是成员）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITATION_ALREADY_PENDING}（存在未过期的待接受邀请）
     */
    EnterpriseInvitationVO createInvitation(Long userId, Long enterpriseId, EnterpriseInvitationCreateRequest request);

    /**
     * 接受企业成员邀请：被邀请人凭一次性令牌，把登录账号加入企业并记录接受信息。
     *
     * <p><strong>定位方式：</strong>令牌即凭证，系统只存其 SHA-256 哈希，
     * 因此先哈希请求中的明文令牌再按 {@code token_hash} 精确查库定位邀请
     * （配合唯一索引，令牌必然唯一）。</p>
     *
     * <p><strong>校验顺序：</strong>令牌无法定位邀请 → 404；邀请已被接受 →
     * 409；邀请已被撤销 → 409；邀请已过期（含被惰性置为 EXPIRED 的）→ 400；
     * 当前登录邮箱与被邀请邮箱不一致 → 403；当前用户已存在该企业成员关系
     * （含 DISABLED）→ 409。</p>
     *
     * <p><strong>事务边界：</strong>「插入成员关系」与「邀请流转为 ACCEPTED」
     * 必须一起成功或一起失败（{@code @Transactional}），不会出现
     * 「成员已加入但邀请仍 PENDING」的脏状态。</p>
     *
     * @param userId  当前登录用户 ID（被邀请人）
     * @param request 接受邀请请求（明文令牌）
     * @return 接受后的邀请视图（状态 ACCEPTED、含接受时间；不再包含令牌）
     * @throws io.github.qwertyhgb.knowflow.common.exception.BusinessException
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITATION_NOT_FOUND}（令牌无法定位邀请）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITATION_EXPIRED}（邀请已过期）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITATION_ALREADY_ACCEPTED}（邀请已被接受）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITATION_REVOKED}（邀请已被撤销）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITATION_EMAIL_MISMATCH}（登录邮箱与被邀请邮箱不一致）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITEE_ALREADY_MEMBER}（当前用户已是成员）
     */
    EnterpriseInvitationVO acceptInvitation(Long userId, InvitationAcceptRequest request);

    /**
     * 查询企业的全部邀请记录（企业视角的邀请管理列表）。
     *
     * <p><strong>权限：</strong>企业不存在 → 404；当前用户非该企业正常成员、
     * 成员被禁用或角色为 MEMBER → 403。仅管理角色（OWNER/ADMIN）可查看邀请列表，
     * 与「创建邀请」权限一致——邀请信息（谁被邀请、授予什么角色）比成员列表更敏感。</p>
     *
     * <p>返回前会把该企业「已过期但仍标记 PENDING」的记录批量更新为 EXPIRED；
     * 随后按创建时间倒序返回（最新邀请在前），暂不分页（与成员列表保持一致）。
     * 不返回明文令牌（{@code token} 仅创建时返回一次）。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 目标企业 ID
     * @return 邀请视图列表；无任何记录时为空列表，不返回 {@code null}
     * @throws io.github.qwertyhgb.knowflow.common.exception.BusinessException
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#NOT_FOUND}（企业不存在）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#FORBIDDEN}（非成员、成员被禁用或角色无权限）
     */
    List<EnterpriseInvitationVO> listEnterpriseInvitations(Long userId, Long enterpriseId);

    /**
     * 查询当前登录邮箱收到的待处理邀请（被邀请人视角）。
     *
     * <p>仅返回「PENDING 且未过期」的邀请，并关联企业名称——被邀请人需要知道
     * 是哪家企业发来的邀请；顺带把该邮箱「已过期但仍标记 PENDING」的历史记录
     * 批量惰性更新为 EXPIRED。</p>
     *
     * <p>按过期时间升序返回（最先过期的排在最前，提示优先处理），暂不分页。</p>
     *
     * @param userId 当前登录用户 ID
     * @return 待处理邀请视图列表（含企业名称，不含令牌）；无记录时为空列表
     */
    List<EnterpriseInvitationVO> listMyPendingInvitations(Long userId);

    /**
     * 撤销（取消）企业邀请：把 PENDING 邀请流转为 REVOKED。
     *
     * <p><strong>权限：</strong>企业不存在 → 404；当前用户非该企业正常成员、
     * 成员被禁用或角色为 MEMBER → 403。OWNER/ADMIN 均可撤销该企业任意
     * PENDING 邀请（与创建邀请权限一致）。</p>
     *
     * <p><strong>校验顺序：</strong>邀请不存在或不属于该企业 → 404（不泄露
     * 邀请存在性）；邀请已被接受 → 409；邀请已被撤销 → 409；邀请已过期
     * （含被惰性置为 EXPIRED 的）→ 400。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 目标企业 ID
     * @param invitationId 目标邀请 ID
     * @return 撤销后的邀请视图（状态 REVOKED）
     * @throws io.github.qwertyhgb.knowflow.common.exception.BusinessException
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#NOT_FOUND}（企业或邀请不存在）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#FORBIDDEN}（非成员、成员被禁用或角色无权限）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITATION_ALREADY_ACCEPTED}（邀请已被接受）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITATION_REVOKED}（邀请已被撤销）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#INVITATION_EXPIRED}（邀请已过期）
     */
    EnterpriseInvitationVO revokeInvitation(Long userId, Long enterpriseId, Long invitationId);
}
