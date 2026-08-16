package io.github.qwertyhgb.knowflow.enterprise.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseInvitationCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.InvitationAcceptRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseInvitation;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseInvitationStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseInvitationMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseMembershipChecker;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseInvitationVO;
import io.github.qwertyhgb.knowflow.user.entity.User;
import io.github.qwertyhgb.knowflow.user.enums.UserStatus;
import io.github.qwertyhgb.knowflow.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创建成员邀请的单元测试：冻结 Clock 断言时间与令牌哈希，
 * 覆盖角色分层、邮箱归一化、重复邀请与惰性过期等业务规则。
 */
@ExtendWith(MockitoExtension.class)
class EnterpriseInvitationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    /** 与 application.yml 的 invitation-ttl 一致，期望过期时间据此计算。 */
    private static final Duration TTL = Duration.ofDays(7);

    /** 内置角色 ID 桩：OWNER / ADMIN / MEMBER（企业角色记录由 enterpriseRoleMapper 桩提供）。 */
    private static final Long OWNER_ROLE_ID = 100L;
    private static final Long ADMIN_ROLE_ID = 200L;
    private static final Long MEMBER_ROLE_ID = 300L;

    @Mock
    private EnterpriseMapper enterpriseMapper;

    @Mock
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @Mock
    private EnterpriseInvitationMapper enterpriseInvitationMapper;

    @Mock
    private EnterpriseRoleMapper enterpriseRoleMapper;

    @Mock
    private UserMapper userMapper;

    private EnterpriseInvitationServiceImpl invitationService;

    @BeforeAll
    static void initializeMyBatisMetadata() {
        // LambdaUpdateWrapper#set 会立即解析属性到列名。Mockito 单测不启动 Spring，
        // 因此必须自行初始化实体元数据，保证本测试可脱离其他测试、单独运行。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, EnterpriseInvitation.class);
        TableInfoHelper.initTableInfo(assistant, EnterpriseMember.class);
        TableInfoHelper.initTableInfo(assistant, EnterpriseRole.class);
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // 使用真实 checker（内部走 enterpriseMapper / enterpriseMemberMapper 两个 mock），
        // 保持既有 exists / selectOne 桩的语义不变。
        EnterpriseMembershipChecker membershipChecker =
                new EnterpriseMembershipChecker(enterpriseMapper, enterpriseMemberMapper);
        invitationService = new EnterpriseInvitationServiceImpl(
                enterpriseMapper, enterpriseMemberMapper, enterpriseInvitationMapper,
                enterpriseRoleMapper, userMapper, membershipChecker, clock, TTL);
    }

    @Test
    void shouldCreateInvitationWithTokenHashAndExpiry() {
        stubInviterContext(EnterpriseMemberRole.OWNER);
        // 被邀请邮箱尚未注册：查询用户返回 null 也应允许创建邀请。
        when(userMapper.selectOne(any())).thenReturn(null);
        when(enterpriseInvitationMapper.insert(any(EnterpriseInvitation.class)))
                .thenAnswer(invocation -> {
                    EnterpriseInvitation invitation = invocation.getArgument(0);
                    invitation.setId(100L);
                    return 1;
                });

        // 邮箱刻意混入空白与大小写，验证归一化（strip + 小写）。
        EnterpriseInvitationVO result = invitationService.createInvitation(
                7L, 1L, request("  Invitee@Example.COM  ", EnterpriseMemberRole.MEMBER));

        // —— 落库实体断言 ——
        ArgumentCaptor<EnterpriseInvitation> invitationCaptor =
                ArgumentCaptor.forClass(EnterpriseInvitation.class);
        verify(enterpriseInvitationMapper).insert(invitationCaptor.capture());
        EnterpriseInvitation saved = invitationCaptor.getValue();
        assertEquals(1L, saved.getEnterpriseId());
        assertEquals(7L, saved.getInviterUserId());
        assertEquals("invitee@example.com", saved.getInviteeEmail(), "邮箱应归一化为小写并无空白");
        assertEquals(EnterpriseMemberRole.MEMBER, saved.getMemberRole());
        assertEquals(EnterpriseInvitationStatus.PENDING, saved.getStatus());
        assertEquals(NOW.plus(TTL), saved.getExpiresAt(), "过期时间应为当前时间 + TTL");
        assertEquals(NOW, saved.getCreatedAt());
        assertEquals(NOW, saved.getUpdatedAt());
        assertNull(saved.getAcceptedByUserId(), "创建时不应有接受人");
        assertNull(saved.getAcceptedAt(), "创建时不应有接受时间");

        // —— 令牌安全断言：明文只出现在返回值，库中只存哈希 ——
        assertNotNull(result.getToken(), "创建响应应返回明文令牌");
        assertEquals(32, result.getToken().length(), "令牌应为 32 位十六进制");
        assertNotEquals(result.getToken(), saved.getTokenHash(), "库中不得保存明文令牌");
        assertEquals(sha256Hex(result.getToken()), saved.getTokenHash(),
                "token_hash 应为明文令牌的 SHA-256 哈希");

        // —— 返回 VO 断言 ——
        assertEquals(100L, result.getId());
        assertEquals("invitee@example.com", result.getInviteeEmail());
        assertEquals(EnterpriseMemberRole.MEMBER, result.getRole());
        assertEquals(EnterpriseInvitationStatus.PENDING, result.getStatus());
        assertEquals(NOW.plus(TTL), result.getExpiresAt());
    }

    @Test
    void shouldAllowOwnerToInviteAdmin() {
        stubInviterContext(EnterpriseMemberRole.OWNER);
        when(enterpriseInvitationMapper.insert(any(EnterpriseInvitation.class))).thenReturn(1);

        invitationService.createInvitation(7L, 1L, request("admin@example.com", EnterpriseMemberRole.ADMIN));

        ArgumentCaptor<EnterpriseInvitation> captor = ArgumentCaptor.forClass(EnterpriseInvitation.class);
        verify(enterpriseInvitationMapper).insert(captor.capture());
        assertEquals(EnterpriseMemberRole.ADMIN, captor.getValue().getMemberRole(),
                "OWNER 应可邀请 ADMIN");
    }

    @Test
    void shouldAllowAdminToInviteMemberOnly() {
        stubInviterContext(EnterpriseMemberRole.ADMIN);
        when(enterpriseInvitationMapper.insert(any(EnterpriseInvitation.class))).thenReturn(1);

        invitationService.createInvitation(7L, 1L, request("member@example.com", EnterpriseMemberRole.MEMBER));

        ArgumentCaptor<EnterpriseInvitation> captor = ArgumentCaptor.forClass(EnterpriseInvitation.class);
        verify(enterpriseInvitationMapper).insert(captor.capture());
        assertEquals(EnterpriseMemberRole.MEMBER, captor.getValue().getMemberRole());
    }

    @Test
    void shouldRejectWhenEnterpriseMissing() {
        when(enterpriseMapper.selectById(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.createInvitation(7L, 1L, request("a@example.com", EnterpriseMemberRole.MEMBER)));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void shouldRejectWhenInviterIsNotMember() {
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.createInvitation(7L, 1L, request("a@example.com", EnterpriseMemberRole.MEMBER)));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void shouldRejectWhenInviterMemberDisabled() {
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.OWNER, EnterpriseMemberStatus.DISABLED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.createInvitation(7L, 1L, request("a@example.com", EnterpriseMemberRole.MEMBER)));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void shouldAllowPlainMemberToInvite() {
        // MEMBER 角色也能发起邀请：管理权限由 Controller 的 @PreAuthorize 校验，
        // Service 只保证成员身份，不再按角色拒绝（分层规则仍限制不能邀请 OWNER/ADMIN）。
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.MEMBER, EnterpriseMemberStatus.NORMAL));
        when(enterpriseRoleMapper.selectById(MEMBER_ROLE_ID))
                .thenReturn(roleEntity(EnterpriseMemberRole.MEMBER));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "member@example.com"));
        when(enterpriseInvitationMapper.insert(any(EnterpriseInvitation.class))).thenReturn(1);

        invitationService.createInvitation(7L, 1L, request("a@example.com", EnterpriseMemberRole.MEMBER));

        verify(enterpriseInvitationMapper).insert(any(EnterpriseInvitation.class));
    }

    @Test
    void shouldRejectGrantingOwnerRole() {
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.OWNER, EnterpriseMemberStatus.NORMAL));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.createInvitation(7L, 1L, request("a@example.com", EnterpriseMemberRole.OWNER)));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "OWNER 角色不允许通过邀请授予");
    }

    @Test
    void shouldRejectAdminInvitingAdmin() {
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.ADMIN, EnterpriseMemberStatus.NORMAL));
        // 分层规则按 roleId 关联的 enterprise_role.code 判断邀请人角色。
        when(enterpriseRoleMapper.selectById(ADMIN_ROLE_ID))
                .thenReturn(roleEntity(EnterpriseMemberRole.ADMIN));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.createInvitation(7L, 1L, request("a@example.com", EnterpriseMemberRole.ADMIN)));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "ADMIN 越权邀请 ADMIN 应被拒绝");
    }

    @Test
    void shouldRejectSelfInvitation() {
        stubInviterContext(EnterpriseMemberRole.OWNER);

        // 邀请邮箱与当前用户邮箱仅大小写不同：归一化后相同，应识别为邀请自己。
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.createInvitation(7L, 1L, request("Owner@Example.COM", EnterpriseMemberRole.MEMBER)));
        assertEquals(ErrorCode.SELF_INVITATION_NOT_ALLOWED, ex.getErrorCode());
    }

    @Test
    void shouldRejectWhenInviteeAlreadyMember() {
        stubInviterContext(EnterpriseMemberRole.OWNER);
        when(userMapper.selectOne(any())).thenReturn(user(8L, "invitee@example.com"));
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.createInvitation(7L, 1L, request("invitee@example.com", EnterpriseMemberRole.MEMBER)));
        assertEquals(ErrorCode.INVITEE_ALREADY_MEMBER, ex.getErrorCode());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<EnterpriseMember>> memberQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(enterpriseMemberMapper).exists(memberQueryCaptor.capture());
        assertFalse(memberQueryCaptor.getValue().getSqlSegment().contains("status"),
                "禁用成员也属于已有成员关系，重复邀请检查不能只查询 NORMAL 状态");
    }

    @Test
    void shouldRejectWhenPendingInvitationStillAlive() {
        stubInviterContext(EnterpriseMemberRole.OWNER);
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofHours(1))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.createInvitation(7L, 1L, request("invitee@example.com", EnterpriseMemberRole.MEMBER)));
        assertEquals(ErrorCode.INVITATION_ALREADY_PENDING, ex.getErrorCode());
        verify(enterpriseInvitationMapper, never()).insert(any(EnterpriseInvitation.class));
    }

    @Test
    void shouldExpireStalePendingAndCreateNewInvitation() {
        stubInviterContext(EnterpriseMemberRole.OWNER);
        // 已过期但仍标记 PENDING 的旧记录：应先置为 EXPIRED，再创建新邀请。
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.minusSeconds(1)));
        when(enterpriseInvitationMapper.insert(any(EnterpriseInvitation.class)))
                .thenAnswer(invocation -> {
                    EnterpriseInvitation invitation = invocation.getArgument(0);
                    invitation.setId(101L);
                    return 1;
                });

        EnterpriseInvitationVO result = invitationService.createInvitation(
                7L, 1L, request("invitee@example.com", EnterpriseMemberRole.MEMBER));

        // 旧记录被条件更新为 EXPIRED，不能覆盖并发产生的 ACCEPTED / REVOKED 终态。
        ArgumentCaptor<LambdaUpdateWrapper<EnterpriseInvitation>> expireCaptor = ArgumentCaptor.captor();
        verify(enterpriseInvitationMapper).update(isNull(), expireCaptor.capture());
        LambdaUpdateWrapper<?> expireWrapper = (LambdaUpdateWrapper<?>) expireCaptor.getValue();
        assertTrue(expireWrapper.getSqlSegment().contains("status"));
        assertTrue(expireWrapper.getSqlSegment().contains("expires_at"));
        assertTrue(expireCaptor.getValue().getSqlSet().contains("status"));

        // 新邀请正常创建，且返回了新的明文令牌。
        assertEquals(EnterpriseInvitationStatus.PENDING, result.getStatus());
        assertEquals(NOW.plus(TTL), result.getExpiresAt());
        assertNotNull(result.getToken());
    }

    // -------------------- 接受邀请 --------------------

    @Test
    void shouldAcceptInvitationAndCreateMemberRecord() {
        // 定位邀请：PENDING 且在有效期内；登录邮箱与被邀请邮箱一致。
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1))));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "invitee@example.com"));
        // 邀请授予 MEMBER 角色：接受时按「企业 + 角色编码」查询目标企业的内置角色 ID。
        when(enterpriseRoleMapper.selectOne(any()))
                .thenReturn(roleEntity(EnterpriseMemberRole.MEMBER));
        when(enterpriseMemberMapper.insert(any(EnterpriseMember.class))).thenReturn(1);
        when(enterpriseInvitationMapper.update(any(EnterpriseInvitation.class), anyInvitationWrapper()))
                .thenReturn(1);

        EnterpriseInvitationVO result = invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32)));

        // —— 成员关系断言：角色经 roleId 关联邀请授予的角色 ——
        ArgumentCaptor<EnterpriseMember> memberCaptor = ArgumentCaptor.forClass(EnterpriseMember.class);
        verify(enterpriseMemberMapper).insert(memberCaptor.capture());
        EnterpriseMember savedMember = memberCaptor.getValue();
        assertEquals(1L, savedMember.getEnterpriseId(), "成员应加入邀请指向的企业");
        assertEquals(7L, savedMember.getUserId());
        assertEquals(MEMBER_ROLE_ID, savedMember.getRoleId(), "角色应取邀请时授予的角色对应 ID");
        assertEquals(EnterpriseMemberStatus.NORMAL, savedMember.getStatus());
        assertEquals(NOW, savedMember.getJoinedAt());
        assertEquals(NOW, savedMember.getCreatedAt());
        assertEquals(NOW, savedMember.getUpdatedAt());

        // —— 邀请状态流转断言 ——
        ArgumentCaptor<EnterpriseInvitation> acceptCaptor = ArgumentCaptor.forClass(EnterpriseInvitation.class);
        ArgumentCaptor<LambdaUpdateWrapper<EnterpriseInvitation>> transitionCaptor = ArgumentCaptor.captor();
        verify(enterpriseInvitationMapper).update(acceptCaptor.capture(), transitionCaptor.capture());
        EnterpriseInvitation savedInvitation = acceptCaptor.getValue();
        assertEquals(EnterpriseInvitationStatus.ACCEPTED, savedInvitation.getStatus());
        assertEquals(7L, savedInvitation.getAcceptedByUserId());
        assertEquals(NOW, savedInvitation.getAcceptedAt());
        assertEquals(NOW, savedInvitation.getUpdatedAt());
        assertTrue(transitionCaptor.getValue().getSqlSegment().contains("status"),
                "接受邀请必须限定原状态为 PENDING");
        assertTrue(transitionCaptor.getValue().getSqlSegment().contains("expires_at"),
                "接受邀请必须在 SQL 层再次限定尚未过期");

        // —— 响应断言：状态已流转、含接受时间，且不再携带明文令牌 ——
        assertEquals(EnterpriseInvitationStatus.ACCEPTED, result.getStatus());
        assertEquals(NOW, result.getAcceptedAt());
        assertNull(result.getToken(), "接受响应不应携带令牌");
    }

    @Test
    void shouldRejectUnknownToken() {
        when(enterpriseInvitationMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32))));
        assertEquals(ErrorCode.INVITATION_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void shouldRejectAlreadyAcceptedInvitation() {
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.ACCEPTED, NOW.plus(Duration.ofDays(1))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32))));
        assertEquals(ErrorCode.INVITATION_ALREADY_ACCEPTED, ex.getErrorCode());
        verify(enterpriseInvitationMapper, never())
                .update(any(EnterpriseInvitation.class), anyInvitationWrapper());
    }

    @Test
    void shouldRejectRevokedInvitation() {
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.REVOKED, NOW.plus(Duration.ofDays(1))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32))));
        assertEquals(ErrorCode.INVITATION_REVOKED, ex.getErrorCode());
    }

    @Test
    void shouldRejectInvitationAlreadyMarkedExpired() {
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.EXPIRED, NOW.minusSeconds(1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32))));
        assertEquals(ErrorCode.INVITATION_EXPIRED, ex.getErrorCode());
    }

    @Test
    void shouldRejectExpiredPendingWithoutWritingInsideRolledBackTransaction() {
        // 状态仍是 PENDING 但已过有效期：直接按 expires_at 判定为过期。
        // 若在抛 BusinessException 前更新，整个事务会回滚，形成“看似写入、实际未落库”的假象。
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.minusSeconds(1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32))));
        assertEquals(ErrorCode.INVITATION_EXPIRED, ex.getErrorCode());
        verify(enterpriseInvitationMapper, never()).update(any(), anyInvitationWrapper());
    }

    @Test
    void shouldRejectWhenLoginEmailMismatches() {
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1))));
        // 登录邮箱与被邀请邮箱不一致：令牌有效但持有者不是被邀请人。
        when(userMapper.selectById(7L)).thenReturn(user(7L, "someone-else@example.com"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32))));
        assertEquals(ErrorCode.INVITATION_EMAIL_MISMATCH, ex.getErrorCode());
    }

    @Test
    void shouldRejectWhenAlreadyMember() {
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1))));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "invitee@example.com"));
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32))));
        assertEquals(ErrorCode.INVITEE_ALREADY_MEMBER, ex.getErrorCode());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<EnterpriseMember>> memberQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(enterpriseMemberMapper).exists(memberQueryCaptor.capture());
        assertFalse(memberQueryCaptor.getValue().getSqlSegment().contains("status"),
                "接受邀请前应拒绝任何已有成员关系，含 DISABLED 状态");
    }

    @Test
    void shouldTranslateDuplicateMemberInsertToAlreadyMember() {
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1))));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "invitee@example.com"));
        // 接受前按「企业 + 邀请角色」查询目标企业的内置角色 ID。
        when(enterpriseRoleMapper.selectOne(any()))
                .thenReturn(roleEntity(EnterpriseMemberRole.MEMBER));
        // 并发场景：第 5 步检查通过后，另一请求已插入成员关系，本请求撞唯一索引。
        when(enterpriseMemberMapper.insert(any(EnterpriseMember.class)))
                .thenThrow(new DuplicateKeyException("duplicate member"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32))));
        assertEquals(ErrorCode.INVITEE_ALREADY_MEMBER, ex.getErrorCode());
        verify(enterpriseInvitationMapper, never())
                .update(any(EnterpriseInvitation.class), anyInvitationWrapper());
    }

    @Test
    void shouldReportConcurrentRevokeWhenAcceptTransitionLosesRace() {
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1))));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "invitee@example.com"));
        when(enterpriseRoleMapper.selectOne(any()))
                .thenReturn(roleEntity(EnterpriseMemberRole.MEMBER));
        when(enterpriseMemberMapper.insert(any(EnterpriseMember.class))).thenReturn(1);
        when(enterpriseInvitationMapper.update(any(EnterpriseInvitation.class), anyInvitationWrapper()))
                .thenReturn(0);
        when(enterpriseInvitationMapper.selectById(100L))
                .thenReturn(invitation(EnterpriseInvitationStatus.REVOKED, NOW.plus(Duration.ofDays(1))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.acceptInvitation(7L, acceptRequest("a".repeat(32))));

        assertEquals(ErrorCode.INVITATION_REVOKED, ex.getErrorCode());
        verify(enterpriseInvitationMapper).selectById(100L);
    }

    // -------------------- 企业邀请列表 --------------------

    @Test
    void shouldListEnterpriseInvitationsForManager() {
        // 管理成员（OWNER）可查看企业全部邀请。
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.OWNER, EnterpriseMemberStatus.NORMAL));
        when(enterpriseInvitationMapper.selectList(any()))
                .thenReturn(List.of(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1))),
                        invitation(EnterpriseInvitationStatus.ACCEPTED, NOW.plus(Duration.ofDays(2)))));

        List<EnterpriseInvitationVO> result = invitationService.listEnterpriseInvitations(7L, 1L);

        assertEquals(2, result.size());
        assertEquals(EnterpriseInvitationStatus.PENDING, result.get(0).getStatus());
        assertEquals(EnterpriseInvitationStatus.ACCEPTED, result.get(1).getStatus());
        assertEquals("invitee@example.com", result.get(0).getInviteeEmail());
        assertNull(result.get(0).getToken(), "列表不应返回明文令牌");
        assertNull(result.get(0).getEnterpriseName(), "企业视角列表不需要企业名称");

        // 返回列表前应按企业 + PENDING + 过期时间批量收敛状态。
        ArgumentCaptor<LambdaUpdateWrapper<EnterpriseInvitation>> expiryCaptor = ArgumentCaptor.captor();
        verify(enterpriseInvitationMapper).update(isNull(), expiryCaptor.capture());
        assertTrue(expiryCaptor.getValue().getSqlSegment().contains("enterprise_id"));
        assertTrue(expiryCaptor.getValue().getSqlSegment().contains("expires_at"));
        assertTrue(expiryCaptor.getValue().getSqlSet().contains("status"));

        // 查询条件应限定目标企业（SQL 层过滤）。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<EnterpriseInvitation>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(enterpriseInvitationMapper).selectList(wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("enterprise_id"),
                "应按企业 ID 查询邀请");
    }

    @Test
    void shouldRejectListingWhenEnterpriseMissing() {
        when(enterpriseMapper.selectById(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.listEnterpriseInvitations(7L, 1L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void shouldAllowPlainMemberToListInvitations() {
        // MEMBER 角色也能查看邀请列表：管理权限由 @PreAuthorize 校验，Service 只校验成员身份。
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.MEMBER, EnterpriseMemberStatus.NORMAL));
        when(enterpriseInvitationMapper.selectList(any())).thenReturn(List.of());

        List<EnterpriseInvitationVO> result = invitationService.listEnterpriseInvitations(7L, 1L);

        assertTrue(result.isEmpty(), "正常成员（含 MEMBER）应可查看邀请列表");
    }

    // -------------------- 我的待处理邀请 --------------------

    @Test
    void shouldListMyPendingInvitationsWithEnterpriseName() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "invitee@example.com"));
        when(enterpriseInvitationMapper.selectList(any()))
                .thenReturn(List.of(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1)))));
        when(enterpriseMapper.selectByIds(any())).thenReturn(List.of(enterprise()));

        List<EnterpriseInvitationVO> result = invitationService.listMyPendingInvitations(7L);

        assertEquals(1, result.size());
        assertEquals("测试企业", result.get(0).getEnterpriseName(), "被邀请人需要知道是哪家企业");
        assertEquals(EnterpriseInvitationStatus.PENDING, result.get(0).getStatus());
        assertNull(result.get(0).getToken(), "列表不应返回明文令牌");

        // 批量惰性过期：按邮箱 + PENDING + 已过期条件批量置为 EXPIRED。
        ArgumentCaptor<LambdaUpdateWrapper<EnterpriseInvitation>> updateCaptor = ArgumentCaptor.captor();
        verify(enterpriseInvitationMapper).update(isNull(), updateCaptor.capture());
        assertTrue(updateCaptor.getValue().getSqlSet().contains("status"),
                "批量更新应显式 SET 状态为 EXPIRED");

        // 待处理查询应按邮箱过滤。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<EnterpriseInvitation>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(enterpriseInvitationMapper).selectList(wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("invitee_email"),
                "应按被邀请邮箱查询待处理邀请");
    }

    @Test
    void shouldReturnEmptyWhenNoPendingInvitations() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "invitee@example.com"));
        when(enterpriseInvitationMapper.selectList(any())).thenReturn(List.of());

        List<EnterpriseInvitationVO> result = invitationService.listMyPendingInvitations(7L);

        assertTrue(result.isEmpty());
        verify(enterpriseMapper, never()).selectByIds(any());
    }

    // -------------------- 撤销邀请 --------------------

    @Test
    void shouldRevokePendingInvitation() {
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.OWNER, EnterpriseMemberStatus.NORMAL));
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1))));
        when(enterpriseInvitationMapper.update(any(EnterpriseInvitation.class), anyInvitationWrapper()))
                .thenReturn(1);

        EnterpriseInvitationVO result = invitationService.revokeInvitation(7L, 1L, 100L);

        ArgumentCaptor<EnterpriseInvitation> revokeCaptor = ArgumentCaptor.forClass(EnterpriseInvitation.class);
        ArgumentCaptor<LambdaUpdateWrapper<EnterpriseInvitation>> transitionCaptor = ArgumentCaptor.captor();
        verify(enterpriseInvitationMapper).update(revokeCaptor.capture(), transitionCaptor.capture());
        assertEquals(EnterpriseInvitationStatus.REVOKED, revokeCaptor.getValue().getStatus());
        assertEquals(NOW, revokeCaptor.getValue().getUpdatedAt());
        assertTrue(transitionCaptor.getValue() instanceof LambdaUpdateWrapper<?>);
        assertTrue(transitionCaptor.getValue().getSqlSegment().contains("status"));
        assertTrue(transitionCaptor.getValue().getSqlSegment().contains("expires_at"));

        assertEquals(EnterpriseInvitationStatus.REVOKED, result.getStatus());
        assertNull(result.getToken(), "撤销响应不应携带令牌");
    }

    @Test
    void shouldRejectRevokingWhenInvitationMissingOrNotInEnterprise() {
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.OWNER, EnterpriseMemberStatus.NORMAL));
        when(enterpriseInvitationMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.revokeInvitation(7L, 1L, 999L));
        assertEquals(ErrorCode.INVITATION_NOT_FOUND, ex.getErrorCode(),
                "邀请不存在或不属于该企业统一返回 404");
    }

    @Test
    void shouldAllowPlainMemberToRevoke() {
        // MEMBER 角色也能撤销邀请：管理权限由 @PreAuthorize 校验，Service 只校验成员身份。
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.MEMBER, EnterpriseMemberStatus.NORMAL));
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1))));
        when(enterpriseInvitationMapper.update(any(EnterpriseInvitation.class), anyInvitationWrapper()))
                .thenReturn(1);

        EnterpriseInvitationVO result = invitationService.revokeInvitation(7L, 1L, 100L);

        assertEquals(EnterpriseInvitationStatus.REVOKED, result.getStatus(),
                "正常成员（含 MEMBER）应可撤销邀请");
    }

    @Test
    void shouldRejectRevokingAcceptedInvitation() {
        stubRevokeContext(EnterpriseInvitationStatus.ACCEPTED, NOW.plus(Duration.ofDays(1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.revokeInvitation(7L, 1L, 100L));
        assertEquals(ErrorCode.INVITATION_ALREADY_ACCEPTED, ex.getErrorCode());
    }

    @Test
    void shouldRejectRevokingAlreadyRevokedInvitation() {
        stubRevokeContext(EnterpriseInvitationStatus.REVOKED, NOW.plus(Duration.ofDays(1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.revokeInvitation(7L, 1L, 100L));
        assertEquals(ErrorCode.INVITATION_REVOKED, ex.getErrorCode());
    }

    @Test
    void shouldRejectRevokingExpiredInvitation() {
        stubRevokeContext(EnterpriseInvitationStatus.EXPIRED, NOW.minusSeconds(1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.revokeInvitation(7L, 1L, 100L));
        assertEquals(ErrorCode.INVITATION_EXPIRED, ex.getErrorCode());
    }

    @Test
    void shouldRejectExpiredPendingWhenRevokingWithoutRolledBackWrite() {
        // PENDING 但已过有效期：直接拒绝；过期状态由列表或再次邀请时持久化。
        stubRevokeContext(EnterpriseInvitationStatus.PENDING, NOW.minusSeconds(1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.revokeInvitation(7L, 1L, 100L));
        assertEquals(ErrorCode.INVITATION_EXPIRED, ex.getErrorCode());
        verify(enterpriseInvitationMapper, never()).update(any(), anyInvitationWrapper());
    }

    @Test
    void shouldReportConcurrentAcceptWhenRevokeTransitionLosesRace() {
        stubRevokeContext(EnterpriseInvitationStatus.PENDING, NOW.plus(Duration.ofDays(1)));
        when(enterpriseInvitationMapper.update(any(EnterpriseInvitation.class), anyInvitationWrapper()))
                .thenReturn(0);
        when(enterpriseInvitationMapper.selectById(100L))
                .thenReturn(invitation(EnterpriseInvitationStatus.ACCEPTED, NOW.plus(Duration.ofDays(1))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invitationService.revokeInvitation(7L, 1L, 100L));

        assertEquals(ErrorCode.INVITATION_ALREADY_ACCEPTED, ex.getErrorCode());
        verify(enterpriseInvitationMapper).selectById(100L);
    }

    /**
     * 桩掉撤销邀请的前置上下文：企业存在、当前用户（7L）是 OWNER 正常成员、
     * 目标邀请（100L）存在。各测试在此基础上按需补充 updateById 等后续桩。
     *
     * <p>撤销流程只校验成员身份（不解析角色），因此这里不需要 enterpriseRoleMapper 桩；
     * member() 设置的 roleId 仅是成员记录数据，不会触发角色查询。</p>
     */
    private void stubRevokeContext(EnterpriseInvitationStatus status, Instant expiresAt) {
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(EnterpriseMemberRole.OWNER, EnterpriseMemberStatus.NORMAL));
        when(enterpriseInvitationMapper.selectOne(any()))
                .thenReturn(invitation(status, expiresAt));
    }

    /**
     * 桩掉邀请发起的前置上下文：企业存在、当前用户（7L）是该企业指定角色的正常成员、
     * 邀请人账号信息可查。各测试在此基础上按需补充后续桩。
     */
    private void stubInviterContext(EnterpriseMemberRole role) {
        when(enterpriseMapper.selectById(1L)).thenReturn(enterprise());
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(member(role, EnterpriseMemberStatus.NORMAL));
        when(enterpriseRoleMapper.selectById(roleIdOf(role))).thenReturn(roleEntity(role));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "owner@example.com"));
    }

    private Enterprise enterprise() {
        Enterprise enterprise = new Enterprise();
        enterprise.setId(1L);
        enterprise.setName("测试企业");
        enterprise.setSlug("test");
        enterprise.setStatus(EnterpriseStatus.NORMAL);
        return enterprise;
    }

    private EnterpriseMember member(EnterpriseMemberRole role, EnterpriseMemberStatus status) {
        EnterpriseMember member = new EnterpriseMember();
        member.setId(10L);
        member.setEnterpriseId(1L);
        member.setUserId(7L);
        // 角色经 roleId 关联 enterprise_role（V3 的 member_role 列已退役）。
        member.setRoleId(roleIdOf(role));
        member.setStatus(status);
        return member;
    }

    /** 内置角色枚举 → 角色 ID 桩。 */
    private Long roleIdOf(EnterpriseMemberRole role) {
        return switch (role) {
            case OWNER -> OWNER_ROLE_ID;
            case ADMIN -> ADMIN_ROLE_ID;
            case MEMBER -> MEMBER_ROLE_ID;
        };
    }

    /** 构造企业角色记录（编码即角色语义，供 roleId → code 解析）。 */
    private EnterpriseRole roleEntity(EnterpriseMemberRole role) {
        EnterpriseRole enterpriseRole = new EnterpriseRole();
        enterpriseRole.setId(roleIdOf(role));
        enterpriseRole.setEnterpriseId(1L);
        enterpriseRole.setCode(role.getValue());
        enterpriseRole.setName(role.getValue());
        enterpriseRole.setStatus(EnterpriseRoleStatus.NORMAL);
        return enterpriseRole;
    }

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setNickname("用户" + id);
        user.setStatus(UserStatus.NORMAL);
        return user;
    }

    private EnterpriseInvitation invitation(EnterpriseInvitationStatus status, Instant expiresAt) {
        EnterpriseInvitation invitation = new EnterpriseInvitation();
        invitation.setId(100L);
        invitation.setEnterpriseId(1L);
        invitation.setInviterUserId(7L);
        invitation.setInviteeEmail("invitee@example.com");
        invitation.setMemberRole(EnterpriseMemberRole.MEMBER);
        invitation.setTokenHash("0".repeat(64));
        invitation.setStatus(status);
        invitation.setExpiresAt(expiresAt);
        invitation.setCreatedAt(NOW.minus(Duration.ofDays(1)));
        invitation.setUpdatedAt(NOW.minus(Duration.ofDays(1)));
        return invitation;
    }

    private EnterpriseInvitationCreateRequest request(String email, EnterpriseMemberRole role) {
        EnterpriseInvitationCreateRequest request = new EnterpriseInvitationCreateRequest();
        request.setEmail(email);
        request.setRole(role);
        return request;
    }

    private Wrapper<EnterpriseInvitation> anyInvitationWrapper() {
        return any();
    }

    private InvitationAcceptRequest acceptRequest(String token) {
        InvitationAcceptRequest request = new InvitationAcceptRequest();
        request.setToken(token);
        return request;
    }

    /** 与实现一致的 SHA-256 十六进制计算，用于断言 token_hash 的正确性。 */
    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
