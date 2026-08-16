package io.github.qwertyhgb.knowflow.enterprise.vo;

import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.user.entity.User;
import lombok.Getter;

import java.time.Instant;

/**
 * 企业成员视图：成员关系信息 + 关联的用户公开信息（邮箱、昵称）。
 *
 * <p>仅暴露可安全下发给企业成员的用户信息（邮箱、昵称），
 * <strong>绝不包含 {@code passwordHash}</strong> 等敏感字段。
 * 成员关系与用户分属两张表（{@code enterprise_member} / {@code sys_user}），
 * 由服务层关联后通过 {@link #from(EnterpriseMember, User)} 组装。</p>
 */
@Getter
public class EnterpriseMemberVO {

    private final Long id;

    private final Long userId;

    /** 用户邮箱，来自关联的 {@code sys_user}；用户不存在时为 null。 */
    private final String email;

    /** 用户昵称，来自关联的 {@code sys_user}；用户不存在时为 null。 */
    private final String nickname;

    /** 成员角色（OWNER / ADMIN / MEMBER）。 */
    private final EnterpriseMemberRole role;

    private final EnterpriseMemberStatus status;

    /** 加入企业的时间（UTC）。 */
    private final Instant joinedAt;

    private EnterpriseMemberVO(Long id, Long userId, String email, String nickname,
                               EnterpriseMemberRole role, EnterpriseMemberStatus status, Instant joinedAt) {
        this.id = id;
        this.userId = userId;
        this.email = email;
        this.nickname = nickname;
        this.role = role;
        this.status = status;
        this.joinedAt = joinedAt;
    }

    /**
     * 由成员关系实体与关联用户组装视图。
     *
     * @param member 成员关系记录
     * @param user   关联用户，可能为 null（用户已被删除等异常情况），此时邮箱/昵称留空
     * @return 成员视图
     */
    public static EnterpriseMemberVO from(EnterpriseMember member, User user) {
        return new EnterpriseMemberVO(
                member.getId(),
                member.getUserId(),
                user != null ? user.getEmail() : null,
                user != null ? user.getNickname() : null,
                member.getMemberRole(),
                member.getStatus(),
                member.getJoinedAt());
    }
}
