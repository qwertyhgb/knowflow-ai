package io.github.qwertyhgb.knowflow.enterprise.mapper;

import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseInvitation;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseInvitationStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseStatus;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseService;
import io.github.qwertyhgb.knowflow.user.entity.User;
import io.github.qwertyhgb.knowflow.user.enums.UserStatus;
import io.github.qwertyhgb.knowflow.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Enterprise 基础持久化验证。
 *
 * <p>覆盖 MyBatis-Plus 枚举读写、数据库默认时间、外键依赖和成员唯一约束；
 * 测试事务结束后自动回滚，不污染其他用例。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class EnterprisePersistenceTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private EnterpriseMapper enterpriseMapper;

    @Autowired
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @Autowired
    private EnterpriseInvitationMapper enterpriseInvitationMapper;

    @Autowired
    private EnterpriseService enterpriseService;

    @Test
    @Transactional
    void shouldPersistEnterpriseAndMemberWithEnums() {
        User user = new User();
        user.setEmail("enterprise-owner@example.com");
        user.setPasswordHash("test-password-hash");
        user.setNickname("Enterprise Owner");
        user.setStatus(UserStatus.NORMAL);
        assertEquals(1, userMapper.insert(user));
        assertNotNull(user.getId());

        Enterprise enterprise = new Enterprise();
        enterprise.setName("KnowFlow Test");
        enterprise.setSlug("knowflow-test");
        enterprise.setStatus(EnterpriseStatus.DISABLED);
        assertEquals(1, enterpriseMapper.insert(enterprise));
        assertNotNull(enterprise.getId());

        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(enterprise.getId());
        member.setUserId(user.getId());
        member.setMemberRole(EnterpriseMemberRole.ADMIN);
        member.setStatus(EnterpriseMemberStatus.DISABLED);
        assertEquals(1, enterpriseMemberMapper.insert(member));
        assertNotNull(member.getId());

        Enterprise savedEnterprise = enterpriseMapper.selectById(enterprise.getId());
        assertEquals(EnterpriseStatus.DISABLED, savedEnterprise.getStatus());
        assertNotNull(savedEnterprise.getCreatedAt());
        assertNotNull(savedEnterprise.getUpdatedAt());

        EnterpriseMember savedMember = enterpriseMemberMapper.selectById(member.getId());
        assertEquals(EnterpriseMemberRole.ADMIN, savedMember.getMemberRole());
        assertEquals(EnterpriseMemberStatus.DISABLED, savedMember.getStatus());
        assertNotNull(savedMember.getJoinedAt());
        assertNotNull(savedMember.getCreatedAt());
        assertNotNull(savedMember.getUpdatedAt());

        EnterpriseMember duplicate = new EnterpriseMember();
        duplicate.setEnterpriseId(enterprise.getId());
        duplicate.setUserId(user.getId());
        duplicate.setMemberRole(EnterpriseMemberRole.MEMBER);
        duplicate.setStatus(EnterpriseMemberStatus.NORMAL);
        assertThrows(DuplicateKeyException.class, () -> enterpriseMemberMapper.insert(duplicate));
    }

    @Test
    void shouldRollbackEnterpriseWhenOwnerMemberInsertFails() {
        long enterpriseCountBefore = enterpriseMapper.selectCount(null);
        EnterpriseCreateRequest request = new EnterpriseCreateRequest();
        request.setName("Rollback Test");

        // 不存在的用户触发 enterprise_member 外键失败，企业记录必须随事务一起回滚。
        assertThrows(DataIntegrityViolationException.class,
                () -> enterpriseService.createEnterprise(Long.MAX_VALUE, request));

        assertEquals(enterpriseCountBefore, enterpriseMapper.selectCount(null),
                "所有者成员写入失败时，不应残留无所有者企业");
    }

    @Test
    @Transactional
    void shouldPersistInvitationWithEnumStringsAndUniqueToken() {
        // 准备邀请的两个外键依赖：邀请人用户 + 目标企业。
        User inviter = new User();
        inviter.setEmail("invitation-inviter@example.com");
        inviter.setPasswordHash("test-password-hash");
        inviter.setNickname("Inviter");
        inviter.setStatus(UserStatus.NORMAL);
        assertEquals(1, userMapper.insert(inviter));

        Enterprise enterprise = new Enterprise();
        enterprise.setName("Invitation Test");
        enterprise.setSlug("invitation-test");
        enterprise.setStatus(EnterpriseStatus.NORMAL);
        assertEquals(1, enterpriseMapper.insert(enterprise));

        EnterpriseInvitation invitation = new EnterpriseInvitation();
        invitation.setEnterpriseId(enterprise.getId());
        invitation.setInviterUserId(inviter.getId());
        invitation.setInviteeEmail("invitee@example.com");
        invitation.setMemberRole(EnterpriseMemberRole.ADMIN);
        invitation.setTokenHash("a".repeat(64));
        invitation.setStatus(EnterpriseInvitationStatus.PENDING);
        // DATETIME(3) 只有毫秒精度：截断到毫秒，保证读写往返后时间点不偏移。
        invitation.setExpiresAt(Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofDays(7)));
        assertEquals(1, enterpriseInvitationMapper.insert(invitation));
        assertNotNull(invitation.getId());

        // 读写往返：字符串状态枚举、角色枚举与毫秒精度时间均不丢失。
        EnterpriseInvitation saved = enterpriseInvitationMapper.selectById(invitation.getId());
        assertEquals(EnterpriseInvitationStatus.PENDING, saved.getStatus());
        assertEquals(EnterpriseMemberRole.ADMIN, saved.getMemberRole());
        assertEquals(invitation.getExpiresAt(), saved.getExpiresAt());
        assertEquals("invitee@example.com", saved.getInviteeEmail());
        assertEquals("a".repeat(64), saved.getTokenHash());
        assertNull(saved.getAcceptedByUserId(), "创建时接受人应为 NULL");
        assertNull(saved.getAcceptedAt(), "创建时接受时间应为 NULL");
        assertNotNull(saved.getCreatedAt(), "created_at 应由数据库默认值填充");

        // 接受流程的状态流转：状态、接受人与接受时间可完整读写往返。
        EnterpriseInvitation acceptUpdate = new EnterpriseInvitation();
        acceptUpdate.setId(invitation.getId());
        acceptUpdate.setStatus(EnterpriseInvitationStatus.ACCEPTED);
        acceptUpdate.setAcceptedByUserId(inviter.getId());
        acceptUpdate.setAcceptedAt(invitation.getExpiresAt());
        assertEquals(1, enterpriseInvitationMapper.updateById(acceptUpdate));

        EnterpriseInvitation accepted = enterpriseInvitationMapper.selectById(invitation.getId());
        assertEquals(EnterpriseInvitationStatus.ACCEPTED, accepted.getStatus());
        assertEquals(inviter.getId(), accepted.getAcceptedByUserId());
        assertEquals(invitation.getExpiresAt(), accepted.getAcceptedAt());
        assertEquals("a".repeat(64), accepted.getTokenHash(), "部分更新不应覆盖其他字段");

        // token_hash 唯一索引：即使换了企业与邮箱，重复哈希也必须被数据库拒绝。
        EnterpriseInvitation duplicateToken = new EnterpriseInvitation();
        duplicateToken.setEnterpriseId(enterprise.getId());
        duplicateToken.setInviterUserId(inviter.getId());
        duplicateToken.setInviteeEmail("other@example.com");
        duplicateToken.setMemberRole(EnterpriseMemberRole.MEMBER);
        duplicateToken.setTokenHash("a".repeat(64));
        duplicateToken.setStatus(EnterpriseInvitationStatus.PENDING);
        duplicateToken.setExpiresAt(invitation.getExpiresAt());
        assertThrows(DuplicateKeyException.class,
                () -> enterpriseInvitationMapper.insert(duplicateToken));
    }
}
