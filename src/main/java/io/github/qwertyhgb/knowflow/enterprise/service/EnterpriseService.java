package io.github.qwertyhgb.knowflow.enterprise.service;

import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseMemberStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseMemberVO;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseVO;

import java.util.List;

/**
 * 企业业务服务。
 */
public interface EnterpriseService {

    /**
     * 创建企业，并自动把当前用户设为该企业的所有者成员。
     *
     * <p>创建企业与写入所有者成员关系必须同时成功或同时失败
     * （实现层使用 {@code @Transactional} 保证）。</p>
     *
     * @param userId  当前登录用户 ID（将成为企业所有者）
     * @param request 创建企业请求参数
     * @return 创建成功后的企业视图
     */
    EnterpriseVO createEnterprise(Long userId, EnterpriseCreateRequest request);

    /**
     * 查询当前用户加入的全部正常企业。
     *
     * <p>仅返回成员关系和企业本身都处于正常状态的记录；
     * 用户未加入任何企业或加入的企业均已禁用时返回空列表，不返回 {@code null}。</p>
     *
     * @param userId 当前登录用户 ID
     * @return 正常企业视图列表（不可变）；无任何记录时为空列表
     */
    List<EnterpriseVO> listMyEnterprises(Long userId);

    /**
     * 查询企业详情，并校验当前用户是否是该企业的正常成员。
     *
     * <p>核心安全规则：<strong>知道 {@code enterpriseId} 不等于有权访问该企业</strong>。
     * 只有同时满足「企业存在」且「当前用户是企业内状态为正常的成员」时才返回详情。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 目标企业 ID
     * @return 企业详情视图
     * @throws io.github.qwertyhgb.knowflow.common.exception.BusinessException
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#NOT_FOUND}（企业不存在）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#FORBIDDEN}（非该企业成员或成员已禁用）
     */
    EnterpriseVO getEnterpriseDetail(Long userId, Long enterpriseId);

    /**
     * 更新企业名称，仅允许该企业的管理成员（OWNER 或 ADMIN）操作。
     *
     * <p>校验顺序：企业不存在 → {@code 404}；成员关系不存在或已被禁用 → {@code 403}；
     * 角色非管理（仅 MEMBER）→ {@code 403}。更新成功后企业 {@code updatedAt} 随之刷新。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 目标企业 ID
     * @param request      更新请求参数
     * @return 更新后的企业视图
     * @throws io.github.qwertyhgb.knowflow.common.exception.BusinessException
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#NOT_FOUND}（企业不存在）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#FORBIDDEN}（非成员、成员被禁用或角色无权限）
     */
    EnterpriseVO updateEnterprise(Long userId, Long enterpriseId, EnterpriseUpdateRequest request);

    /**
     * 查询企业成员列表，并关联用户公开信息（邮箱、昵称）。
     *
     * <p>权限：企业不存在 → {@code 404}；当前用户非该企业正常成员 → {@code 403}。
     * ADMIN 与 MEMBER 均可查看，不做角色限制。成员按加入时间升序返回，
     * 暂不分页。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 目标企业 ID
     * @return 成员视图列表（含用户邮箱/昵称）；无成员时为空列表，不返回 {@code null}
     * @throws io.github.qwertyhgb.knowflow.common.exception.BusinessException
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#NOT_FOUND}（企业不存在）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#FORBIDDEN}（非该企业成员或成员已禁用）
     */
    List<EnterpriseMemberVO> listMembers(Long userId, Long enterpriseId);

    /**
     * 移除企业成员：将目标用户的成员关系状态置为 {@code DISABLED}。
     *
     * <p><strong>权限：</strong>仅企业管理员（OWNER / ADMIN）可操作——
     * MEMBER 无权，返回 403。</p>
     *
     * <p><strong>校验顺序：</strong>企业不存在 → 404；当前用户非该企业正常成员
     * 或角色非管理 → 403；移除操作是自身（管理员不能把自己踢出企业）→ 403；
     * 目标用户不是该企业成员 → 404（不泄露存在性）；目标用户是 OWNER → 400。
     * 目标成员已经是 DISABLED 时幂等成功，不重复写库。</p>
     *
     * <p><strong>事务边界：</strong>本操作只更新 {@code EnterpriseMember.status}，
     * 无需与其他写操作组成事务，但标注 {@code @Transactional} 保持代码风格一致。</p>
     *
     * @param userId       当前登录用户 ID（执行移除的管理员）
     * @param enterpriseId 目标企业 ID
     * @param targetUserId 被移除者的用户 ID
     */
    void removeMember(Long userId, Long enterpriseId, Long targetUserId);

    /**
     * 当前成员主动退出企业。
     *
     * <p>普通 MEMBER 也可以退出；OWNER 不允许主动退出，以避免企业失去所有者。
     * 已经是 DISABLED 的成员重复调用按幂等成功处理。</p>
     *
     * @param userId       当前登录用户 ID，也是要退出的成员 ID
     * @param enterpriseId 目标企业 ID
     */
    void leaveEnterprise(Long userId, Long enterpriseId);

    /**
     * 修改企业成员状态：启用（{@code NORMAL}）或禁用（{@code DISABLED}）目标成员。
     *
     * <p><strong>与 {@link #removeMember} 的关系：</strong>移除成员是「置为
     * DISABLED」的便捷语义；本接口是通用状态管理，同时支持恢复被移除的成员
     * （{@code DISABLED → NORMAL}）。两接口共用相同的权限与边界校验。</p>
     *
     * <p><strong>校验顺序：</strong>企业不存在 → 404；当前用户非该企业正常成员
     * 或角色非管理 → 403；目标用户不是该企业成员 → 404（不泄露存在性）；
     * 目标用户是 OWNER → 400（所有者状态不可修改）；目标是自己 → 403
     * （管理员不能禁用自己，否则企业失去管理出口）。</p>
     *
     * <p><strong>幂等：</strong>请求状态与当前状态相同时直接返回当前成员视图，
     * 不触发更新、不刷新 {@code updatedAt}，重复请求无副作用。</p>
     *
     * @param userId       当前登录用户 ID（执行修改的管理员）
     * @param enterpriseId 目标企业 ID
     * @param targetUserId 目标成员的用户 ID
     * @param request      目标状态（NORMAL / DISABLED）
     * @return 修改后的成员视图（含关联的用户邮箱/昵称）
     * @throws io.github.qwertyhgb.knowflow.common.exception.BusinessException
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#NOT_FOUND}（企业或目标成员不存在）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#FORBIDDEN}（非成员、成员被禁用、角色无权限或修改自己）；
     *         {@link io.github.qwertyhgb.knowflow.common.exception.ErrorCode#CANNOT_REMOVE_OWNER}（目标为 OWNER）
     */
    EnterpriseMemberVO updateMemberStatus(Long userId, Long enterpriseId, Long targetUserId,
                                          EnterpriseMemberStatusUpdateRequest request);
}
