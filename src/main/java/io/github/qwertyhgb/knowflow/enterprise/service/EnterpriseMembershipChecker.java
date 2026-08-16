package io.github.qwertyhgb.knowflow.enterprise.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import org.springframework.stereotype.Component;

/**
 * 企业成员身份校验组件。
 *
 * <p>多租户 SaaS 的安全底线是「有 ID 不代表有权限」——企业作用域的任何操作
 * 都必须先确认「企业存在」且「当前用户是该企业的正常成员」。该逻辑曾被
 * 企业、邀请、部门三个 Service 各自实现，现统一收口到本组件，保证安全判定
 * 单一来源，也让未来知识库/文档等新模块默认带上这套校验。</p>
 *
 * <p><strong>错误码约定：</strong></p>
 * <ul>
 *   <li>企业不存在 → {@link ErrorCode#NOT_FOUND}（先资源后权限，避免把不存在误报成无权限）；</li>
 *   <li>非该企业正常成员 → {@link ErrorCode#FORBIDDEN}。</li>
 * </ul>
 *
 * <p><strong>管理权限边界：</strong>本组件只保证「成员身份」；管理类操作的角色/权限
 * （如 {@code member:remove}）由 Controller 的 {@code @PreAuthorize} 按权限码校验，
 * 与 Service 层的成员校验互为纵深防御，不冲突。</p>
 */
@Component
public class EnterpriseMembershipChecker {

    private final EnterpriseMapper enterpriseMapper;

    private final EnterpriseMemberMapper enterpriseMemberMapper;

    public EnterpriseMembershipChecker(EnterpriseMapper enterpriseMapper,
                                       EnterpriseMemberMapper enterpriseMemberMapper) {
        this.enterpriseMapper = enterpriseMapper;
        this.enterpriseMemberMapper = enterpriseMemberMapper;
    }

    /**
     * 校验企业存在并返回，否则抛 404。
     *
     * @param enterpriseId 目标企业 ID
     * @return 企业实体
     */
    public Enterprise requireEnterprise(Long enterpriseId) {
        Enterprise enterprise = enterpriseMapper.selectById(enterpriseId);
        if (enterprise == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return enterprise;
    }

    /**
     * 校验当前用户是该企业的正常成员（成员关系存在且状态为 NORMAL），否则抛 403。
     *
     * <p>使用 {@code exists} 仅做存在性判断，适合只需要「是否允许」、无需成员实体的场景。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 目标企业 ID
     */
    public void requireActiveMember(Long userId, Long enterpriseId) {
        boolean active = enterpriseMemberMapper.exists(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, userId)
                        .eq(EnterpriseMember::getStatus, EnterpriseMemberStatus.NORMAL));
        if (!active) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * 校验当前用户是该企业的正常成员并返回成员实体，否则抛 403。
     *
     * <p>在 {@link #requireActiveMember(Long, Long)} 的基础上额外返回成员关系，
     * 供需要读取角色等成员信息的场景使用（如邀请业务里的角色分层判断）。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 目标企业 ID
     * @return 正常状态的成员关系实体
     */
    public EnterpriseMember requireActiveMemberEntity(Long userId, Long enterpriseId) {
        EnterpriseMember member = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, userId));
        if (member == null || member.getStatus() != EnterpriseMemberStatus.NORMAL) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return member;
    }
}
