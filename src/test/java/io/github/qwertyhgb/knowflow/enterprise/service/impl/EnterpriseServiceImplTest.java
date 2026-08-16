package io.github.qwertyhgb.knowflow.enterprise.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseMemberStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseMemberVO;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseVO;
import io.github.qwertyhgb.knowflow.user.entity.User;
import io.github.qwertyhgb.knowflow.user.enums.UserStatus;
import io.github.qwertyhgb.knowflow.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnterpriseServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    @Mock
    private EnterpriseMapper enterpriseMapper;

    @Mock
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @Mock
    private UserMapper userMapper;

    private EnterpriseServiceImpl enterpriseService;

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, EnterpriseMember.class);
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        enterpriseService = new EnterpriseServiceImpl(enterpriseMapper, enterpriseMemberMapper, userMapper, clock);
    }

    @Test
    void shouldCreateEnterpriseWithOwnerMember() {
        EnterpriseCreateRequest request = request("  测试企业  ");
        // mock insert：为 enterprise 回填自增主键。
        when(enterpriseMapper.insert(any(Enterprise.class))).thenAnswer(invocation -> {
            Enterprise enterprise = invocation.getArgument(0);
            enterprise.setId(1L);
            return 1;
        });

        EnterpriseVO result = enterpriseService.createEnterprise(7L, request);

        // —— 企业记录断言 ——
        ArgumentCaptor<Enterprise> enterpriseCaptor = ArgumentCaptor.forClass(Enterprise.class);
        verify(enterpriseMapper).insert(enterpriseCaptor.capture());
        Enterprise savedEnterprise = enterpriseCaptor.getValue();
        assertEquals("测试企业", savedEnterprise.getName(), "企业名称应去除首尾空白");
        assertNotNull(savedEnterprise.getSlug(), "slug 应自动生成");
        assertEquals(32, savedEnterprise.getSlug().length(), "slug 应保留完整 UUID 十六进制");
        assertEquals(EnterpriseStatus.NORMAL, savedEnterprise.getStatus());
        assertEquals(NOW, savedEnterprise.getCreatedAt());
        assertEquals(NOW, savedEnterprise.getUpdatedAt());

        // —— 所有者成员关系断言 ——
        ArgumentCaptor<EnterpriseMember> memberCaptor = ArgumentCaptor.forClass(EnterpriseMember.class);
        verify(enterpriseMemberMapper).insert(memberCaptor.capture());
        EnterpriseMember savedMember = memberCaptor.getValue();
        assertEquals(1L, savedMember.getEnterpriseId(), "成员应关联新创建的企业");
        assertEquals(7L, savedMember.getUserId(), "创建者应为成员");
        assertEquals(EnterpriseMemberRole.OWNER, savedMember.getMemberRole(), "创建者角色应为 OWNER");
        assertEquals(EnterpriseMemberStatus.NORMAL, savedMember.getStatus());
        assertEquals(NOW, savedMember.getJoinedAt());
        assertEquals(NOW, savedMember.getCreatedAt());
        assertEquals(NOW, savedMember.getUpdatedAt());

        // —— 返回 VO 断言 ——
        assertEquals(1L, result.getId());
        assertEquals("测试企业", result.getName());
        assertEquals(EnterpriseStatus.NORMAL, result.getStatus());
        assertEquals(NOW, result.getCreatedAt());
    }

    @Test
    void shouldListOnlyActiveEnterprisesForUser() {
        when(enterpriseMemberMapper.selectList(any()))
                .thenReturn(List.of(member(1L, 10L, 7L), member(2L, 20L, 7L)));
        when(enterpriseMapper.selectByIds(any()))
                // 刻意返回与成员关系相反的顺序，验证服务不依赖数据库 IN 查询顺序。
                .thenReturn(List.of(enterprise(20L, "企业B", EnterpriseStatus.NORMAL),
                        enterprise(10L, "企业A", EnterpriseStatus.NORMAL)));

        List<EnterpriseVO> result = enterpriseService.listMyEnterprises(7L);

        assertEquals(List.of(10L, 20L), result.stream().map(EnterpriseVO::getId).toList());
        assertEquals("企业A", result.get(0).getName());
        assertEquals(EnterpriseStatus.NORMAL, result.get(0).getStatus());

        // 查询条件应限定当前用户，且只查正常成员关系（SQL 层过滤）。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<EnterpriseMember>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(enterpriseMemberMapper).selectList(wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("user_id"),
                "应按 userId 查询成员关系");
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("status"),
                "应只查正常状态的成员关系");

        // 批量查询应使用成员关系中的企业 ID。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(enterpriseMapper).selectByIds(idsCaptor.capture());
        assertEquals(List.of(10L, 20L), idsCaptor.getValue());
    }

    @Test
    void shouldFilterOutDisabledEnterprises() {
        when(enterpriseMemberMapper.selectList(any())).thenReturn(List.of(member(1L, 10L, 7L)));
        when(enterpriseMapper.selectByIds(any()))
                .thenReturn(List.of(enterprise(10L, "企业A", EnterpriseStatus.NORMAL),
                        enterprise(11L, "禁用企业", EnterpriseStatus.DISABLED)));

        List<EnterpriseVO> result = enterpriseService.listMyEnterprises(7L);

        // 成员关系正常但企业已被禁用时，不应返回该企业。
        assertEquals(List.of(10L), result.stream().map(EnterpriseVO::getId).toList());
    }

    @Test
    void shouldReturnEmptyListWhenUserHasNoMemberships() {
        when(enterpriseMemberMapper.selectList(any())).thenReturn(List.of());

        List<EnterpriseVO> result = enterpriseService.listMyEnterprises(7L);

        // 空列表而非 null，且不再查询企业表。
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(enterpriseMapper, never()).selectByIds(any());
    }

    @Test
    void shouldReturnEnterpriseDetailForActiveMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);

        EnterpriseVO result = enterpriseService.getEnterpriseDetail(7L, 10L);

        assertEquals(10L, result.getId());
        assertEquals("企业A", result.getName());
        assertEquals(EnterpriseStatus.NORMAL, result.getStatus());

        // 权限查询应同时限定企业、用户与正常状态。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<EnterpriseMember>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(enterpriseMemberMapper).exists(wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("enterprise_id"));
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("user_id"));
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("status"));
    }

    @Test
    void shouldReturnNotFoundWhenEnterpriseDoesNotExist() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.getEnterpriseDetail(7L, 99L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode(), "企业不存在应抛 NOT_FOUND");
        // 企业都不存在，没必要再做成员权限校验。
        verify(enterpriseMemberMapper, never()).exists(any());
    }

    @Test
    void shouldReturnForbiddenWhenUserIsNotMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.exists(any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.getEnterpriseDetail(7L, 10L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode(), "非成员应抛 FORBIDDEN");
    }

    @Test
    void shouldReturnForbiddenWhenMemberIsDisabled() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        // 成员存在但状态为 DISABLED，exists(带 status=NORMAL 条件) 应为 false。
        when(enterpriseMemberMapper.exists(any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.getEnterpriseDetail(7L, 10L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode(), "成员被禁用应抛 FORBIDDEN");
    }

    @Test
    void shouldUpdateEnterpriseNameAsOwner() {
        EnterpriseUpdateRequest request = updateRequest("  新企业名  ");
        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "旧企业名", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner);
        when(enterpriseMapper.updateById(any(Enterprise.class))).thenReturn(1);

        EnterpriseVO result = enterpriseService.updateEnterprise(7L, 10L, request);

        // 持久化补丁只携带允许更新的字段，避免覆盖并发修改的其他列。
        ArgumentCaptor<Enterprise> captor = ArgumentCaptor.forClass(Enterprise.class);
        verify(enterpriseMapper).updateById(captor.capture());
        assertEquals(10L, captor.getValue().getId());
        assertEquals("新企业名", captor.getValue().getName(), "企业名称应去除首尾空白");
        assertEquals(NOW, captor.getValue().getUpdatedAt(), "更新时应刷新 updatedAt");
        assertNull(captor.getValue().getSlug(), "更新补丁不应回写 slug");
        assertNull(captor.getValue().getStatus(), "更新补丁不应回写 status");
        assertNull(captor.getValue().getCreatedAt(), "更新补丁不应回写 createdAt");

        assertEquals(10L, result.getId());
        assertEquals("新企业名", result.getName());
    }

    @Test
    void shouldUpdateEnterpriseNameAsAdmin() {
        EnterpriseUpdateRequest request = updateRequest("新企业名");
        EnterpriseMember admin = member(1L, 10L, 7L);
        admin.setMemberRole(EnterpriseMemberRole.ADMIN);
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "旧企业名", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(admin);
        when(enterpriseMapper.updateById(any(Enterprise.class))).thenReturn(1);

        EnterpriseVO result = enterpriseService.updateEnterprise(7L, 10L, request);

        verify(enterpriseMapper).updateById(any(Enterprise.class));
        assertEquals("新企业名", result.getName(), "ADMIN 应可更新企业名称");
    }

    @Test
    void shouldReturnNotFoundWhenEnterpriseDisappearsDuringUpdate() {
        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        when(enterpriseMapper.selectById(10L))
                .thenReturn(enterprise(10L, "旧企业名", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner);
        when(enterpriseMapper.updateById(any(Enterprise.class))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateEnterprise(7L, 10L, updateRequest("新企业名")));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode(),
                "权限校验后企业被并发删除时不应返回虚假成功");
    }

    @Test
    void shouldReturnNotFoundWhenUpdatingNonExistentEnterprise() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateEnterprise(7L, 99L, updateRequest("新企业名")));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode(), "企业不存在应抛 NOT_FOUND");
        // 企业都不存在，没必要再查成员关系。
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    @Test
    void shouldReturnForbiddenWhenNonMemberUpdates() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "旧企业名", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateEnterprise(7L, 10L, updateRequest("新企业名")));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode(), "非成员应抛 FORBIDDEN");
        verify(enterpriseMapper, never()).updateById(any(Enterprise.class));
    }

    @Test
    void shouldReturnForbiddenWhenDisabledMemberUpdates() {
        EnterpriseMember disabledMember = member(1L, 10L, 7L);
        disabledMember.setMemberRole(EnterpriseMemberRole.ADMIN);
        disabledMember.setStatus(EnterpriseMemberStatus.DISABLED);
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "旧企业名", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(disabledMember);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateEnterprise(7L, 10L, updateRequest("新企业名")));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode(), "成员被禁用应抛 FORBIDDEN");
        verify(enterpriseMapper, never()).updateById(any(Enterprise.class));
    }

    @Test
    void shouldReturnForbiddenWhenPlainMemberUpdates() {
        // 现有 member() 辅助方法默认角色为 MEMBER。
        EnterpriseMember plainMember = member(1L, 10L, 7L);
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "旧企业名", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(plainMember);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateEnterprise(7L, 10L, updateRequest("新企业名")));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode(), "普通成员应抛 FORBIDDEN");
        verify(enterpriseMapper, never()).updateById(any(Enterprise.class));
    }

    @Test
    void shouldListMembersWithUserInfo() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);

        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        EnterpriseMember admin = member(2L, 10L, 8L);
        admin.setMemberRole(EnterpriseMemberRole.ADMIN);
        when(enterpriseMemberMapper.selectList(any())).thenReturn(List.of(owner, admin));

        when(userMapper.selectByIds(any())).thenReturn(List.of(
                user(7L, "alice@example.com", "Alice"),
                user(8L, "bob@example.com", "Bob")));

        List<EnterpriseMemberVO> result = enterpriseService.listMembers(7L, 10L);

        assertEquals(2, result.size());
        // 第 1 个成员：OWNER，关联 alice
        assertEquals(1L, result.get(0).getId());
        assertEquals(7L, result.get(0).getUserId());
        assertEquals("alice@example.com", result.get(0).getEmail());
        assertEquals("Alice", result.get(0).getNickname());
        assertEquals(EnterpriseMemberRole.OWNER, result.get(0).getRole());
        assertEquals(EnterpriseMemberStatus.NORMAL, result.get(0).getStatus());
        assertEquals(NOW, result.get(0).getJoinedAt());
        // 第 2 个成员：ADMIN，关联 bob
        assertEquals(2L, result.get(1).getId());
        assertEquals(8L, result.get(1).getUserId());
        assertEquals("bob@example.com", result.get(1).getEmail());
        assertEquals("Bob", result.get(1).getNickname());
        assertEquals(EnterpriseMemberRole.ADMIN, result.get(1).getRole());
    }

    @Test
    void shouldReturnForbiddenWhenNonMemberListsMembers() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.exists(any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.listMembers(7L, 10L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode(), "非成员应抛 FORBIDDEN");
        // 权限校验未通过，不应继续查成员列表与用户。
        verify(enterpriseMemberMapper, never()).selectList(any());
        verify(userMapper, never()).selectByIds(any());
    }

    @Test
    void shouldReturnNotFoundWhenListingMembersOfNonExistentEnterprise() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.listMembers(7L, 99L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode(), "企业不存在应抛 NOT_FOUND");
        verify(enterpriseMemberMapper, never()).exists(any());
    }

    @Test
    void shouldRemoveMemberWithOwnerRole() {
        // OWNER 可以移除 MEMBER。
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        EnterpriseMember target = member(2L, 10L, 8L);
        target.setMemberRole(EnterpriseMemberRole.MEMBER);
        // selectOne 被调用两次：第一次检查当前用户角色，第二次查询目标成员。
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner, target);
        when(enterpriseMemberMapper.update(isNull(), anyMemberWrapper())).thenReturn(1);

        enterpriseService.removeMember(7L, 10L, 8L);

        ArgumentCaptor<LambdaUpdateWrapper<EnterpriseMember>> wrapperCaptor = ArgumentCaptor.captor();
        verify(enterpriseMemberMapper).update(isNull(), wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSet().contains("status"),
                "应更新 status 字段");
    }

    @Test
    void shouldRemoveMemberWithAdminRole() {
        // ADMIN 也可以移除 MEMBER。
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember admin = member(1L, 10L, 7L);
        admin.setMemberRole(EnterpriseMemberRole.ADMIN);
        EnterpriseMember target = member(2L, 10L, 8L);
        // 两次 selectOne：第一次返回 admin，第二次返回 target。
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(admin, target);
        when(enterpriseMemberMapper.update(isNull(), anyMemberWrapper())).thenReturn(1);

        enterpriseService.removeMember(7L, 10L, 8L);

        verify(enterpriseMemberMapper).update(isNull(), anyMemberWrapper());
    }

    @Test
    void shouldBeIdempotentWhenRemovingDisabledMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        EnterpriseMember disabledTarget = member(2L, 10L, 8L);
        disabledTarget.setStatus(EnterpriseMemberStatus.DISABLED);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner, disabledTarget);

        enterpriseService.removeMember(7L, 10L, 8L);

        verify(enterpriseMemberMapper, never()).update(isNull(), anyMemberWrapper());
    }

    @Test
    void shouldRejectWhenRemoveUpdateAffectsNoRows() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        EnterpriseMember target = member(2L, 10L, 8L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner, target);
        when(enterpriseMemberMapper.update(isNull(), anyMemberWrapper())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.removeMember(7L, 10L, 8L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void shouldRejectWhenTargetIsNotFound() {
        // 目标用户不是该企业成员 → 404（不泄露存在性）。
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember admin = member(1L, 10L, 7L);
        admin.setMemberRole(EnterpriseMemberRole.ADMIN);
        // 第一次 selectOne 返回 admin（通过 requireAdminRole），第二次返回 null（目标不存在）。
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(admin, (EnterpriseMember) null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.removeMember(7L, 10L, 99L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode(), "目标非成员应抛 NOT_FOUND");
        verify(enterpriseMemberMapper, never()).update(isNull(), anyMemberWrapper());
    }

    @Test
    void shouldRejectWhenRemovingOwner() {
        // 不允许移除 OWNER。
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember admin = member(1L, 10L, 7L);
        admin.setMemberRole(EnterpriseMemberRole.ADMIN);
        EnterpriseMember owner = member(2L, 10L, 8L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.removeMember(7L, 10L, 8L));

        assertEquals(ErrorCode.CANNOT_REMOVE_OWNER, exception.getErrorCode(),
                "不能移除企业管理者");
        verify(enterpriseMemberMapper, never()).update(isNull(), anyMemberWrapper());
    }

    @Test
    void shouldRejectWhenRemovingSelf() {
        // 管理员不能把自己踢出企业。
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember self = member(1L, 10L, 7L);
        self.setMemberRole(EnterpriseMemberRole.ADMIN);
        // selectOne 返回的是当前用户自己（当 userId == targetUserId 时查询结果就是它）
        when(enterpriseMemberMapper.selectOne(any()))
                .thenReturn(self);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.removeMember(7L, 10L, 7L));

        assertEquals(ErrorCode.SELF_REMOVE_NOT_ALLOWED, exception.getErrorCode(),
                "不能移除自己");
        verify(enterpriseMemberMapper, never()).update(isNull(), anyMemberWrapper());
    }

    @Test
    void shouldRejectWhenPlainMemberRemoves() {
        // MEMBER 无权移除任何人。
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember disabledMember = member(1L, 10L, 7L);
        disabledMember.setMemberRole(EnterpriseMemberRole.MEMBER);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(disabledMember);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.removeMember(7L, 10L, 8L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode(), "普通成员无权移除");
        // requireAdminRole 会在 selectOne 时拦截，不会执行后续逻辑。
        verify(enterpriseMemberMapper, never()).update(isNull(), anyMemberWrapper());
    }

    @Test
    void shouldReturnNotFoundWhenEnterpriseMissing() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.removeMember(7L, 99L, 8L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode(), "企业不存在应抛 NOT_FOUND");
        // 企业都不存在，没必要再做权限校验。
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    // -------------------- 成员主动退出 --------------------

    @Test
    void shouldAllowMemberToLeaveEnterprise() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember member = member(2L, 10L, 7L);
        member.setMemberRole(EnterpriseMemberRole.MEMBER);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);
        when(enterpriseMemberMapper.update(isNull(), anyMemberWrapper())).thenReturn(1);

        enterpriseService.leaveEnterprise(7L, 10L);

        ArgumentCaptor<LambdaUpdateWrapper<EnterpriseMember>> wrapperCaptor = ArgumentCaptor.captor();
        verify(enterpriseMemberMapper).update(isNull(), wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSet().contains("status"),
                "主动退出应更新成员状态");
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("enterprise_id"),
                "主动退出更新应限定企业");
    }

    @Test
    void shouldRejectOwnerLeavingEnterprise() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.leaveEnterprise(7L, 10L));

        assertEquals(ErrorCode.OWNER_CANNOT_LEAVE, exception.getErrorCode());
        verify(enterpriseMemberMapper, never()).update(isNull(), anyMemberWrapper());
    }

    @Test
    void shouldBeIdempotentWhenLeavingDisabledMembership() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember member = member(2L, 10L, 7L);
        member.setStatus(EnterpriseMemberStatus.DISABLED);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(member);

        enterpriseService.leaveEnterprise(7L, 10L);

        verify(enterpriseMemberMapper, never()).update(isNull(), anyMemberWrapper());
    }

    @Test
    void shouldReturnNotFoundWhenLeavingWithoutMembership() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.leaveEnterprise(7L, 10L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(enterpriseMemberMapper, never()).update(isNull(), anyMemberWrapper());
    }

    @Test
    void shouldReturnNotFoundBeforeMembershipCheckWhenLeavingUnknownEnterprise() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.leaveEnterprise(7L, 99L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    // -------------------- 修改成员状态 --------------------

    @Test
    void shouldDisableMemberAsOwner() {
        // OWNER 禁用 MEMBER（NORMAL → DISABLED）。
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        EnterpriseMember target = member(2L, 10L, 8L);
        // 两次 selectOne：第一次校验操作者角色，第二次查询目标成员。
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner, target);
        when(enterpriseMemberMapper.updateById(any(EnterpriseMember.class))).thenReturn(1);
        when(userMapper.selectById(8L)).thenReturn(user(8L, "bob@example.com", "Bob"));

        EnterpriseMemberVO result = enterpriseService.updateMemberStatus(
                7L, 10L, 8L, statusRequest(EnterpriseMemberStatus.DISABLED));

        // 更新补丁只携带允许变更的字段。
        ArgumentCaptor<EnterpriseMember> captor = ArgumentCaptor.forClass(EnterpriseMember.class);
        verify(enterpriseMemberMapper).updateById(captor.capture());
        assertEquals(2L, captor.getValue().getId());
        assertEquals(EnterpriseMemberStatus.DISABLED, captor.getValue().getStatus());
        assertEquals(NOW, captor.getValue().getUpdatedAt());

        // 返回视图反映新状态，且关联用户公开信息。
        assertEquals(EnterpriseMemberStatus.DISABLED, result.getStatus());
        assertEquals("bob@example.com", result.getEmail());
        assertEquals("Bob", result.getNickname());
    }

    @Test
    void shouldEnableMemberAsAdmin() {
        // ADMIN 恢复被移除的 MEMBER（DISABLED → NORMAL）。
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember admin = member(1L, 10L, 7L);
        admin.setMemberRole(EnterpriseMemberRole.ADMIN);
        EnterpriseMember disabledTarget = member(2L, 10L, 8L);
        disabledTarget.setStatus(EnterpriseMemberStatus.DISABLED);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(admin, disabledTarget);
        when(enterpriseMemberMapper.updateById(any(EnterpriseMember.class))).thenReturn(1);

        EnterpriseMemberVO result = enterpriseService.updateMemberStatus(
                7L, 10L, 8L, statusRequest(EnterpriseMemberStatus.NORMAL));

        ArgumentCaptor<EnterpriseMember> captor = ArgumentCaptor.forClass(EnterpriseMember.class);
        verify(enterpriseMemberMapper).updateById(captor.capture());
        assertEquals(EnterpriseMemberStatus.NORMAL, captor.getValue().getStatus());
        assertEquals(EnterpriseMemberStatus.NORMAL, result.getStatus(), "恢复后状态应为 NORMAL");
    }

    @Test
    void shouldBeIdempotentWhenStatusUnchanged() {
        // 目标已是 DISABLED 又请求 DISABLED：直接返回，不触发更新。
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        EnterpriseMember disabledTarget = member(2L, 10L, 8L);
        disabledTarget.setStatus(EnterpriseMemberStatus.DISABLED);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner, disabledTarget);

        EnterpriseMemberVO result = enterpriseService.updateMemberStatus(
                7L, 10L, 8L, statusRequest(EnterpriseMemberStatus.DISABLED));

        assertEquals(EnterpriseMemberStatus.DISABLED, result.getStatus());
        verify(enterpriseMemberMapper, never()).updateById(any(EnterpriseMember.class));
    }

    @Test
    void shouldRejectWhenTargetNotFoundForStatusUpdate() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember owner = member(1L, 10L, 7L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(owner, (EnterpriseMember) null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateMemberStatus(
                        7L, 10L, 99L, statusRequest(EnterpriseMemberStatus.DISABLED)));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode(), "目标非成员应抛 NOT_FOUND");
    }

    @Test
    void shouldRejectWhenModifyingOwnerStatus() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember admin = member(1L, 10L, 7L);
        admin.setMemberRole(EnterpriseMemberRole.ADMIN);
        EnterpriseMember owner = member(2L, 10L, 8L);
        owner.setMemberRole(EnterpriseMemberRole.OWNER);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(admin, owner);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateMemberStatus(
                        7L, 10L, 8L, statusRequest(EnterpriseMemberStatus.DISABLED)));

        assertEquals(ErrorCode.CANNOT_REMOVE_OWNER, exception.getErrorCode(),
                "OWNER 状态不可修改");
    }

    @Test
    void shouldRejectWhenModifyingOwnStatus() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember admin = member(1L, 10L, 7L);
        admin.setMemberRole(EnterpriseMemberRole.ADMIN);
        // 目标是自己（targetUserId == 7L）：命中 selectOne 返回的还是自己。
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(admin);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateMemberStatus(
                        7L, 10L, 7L, statusRequest(EnterpriseMemberStatus.DISABLED)));

        assertEquals(ErrorCode.SELF_REMOVE_NOT_ALLOWED, exception.getErrorCode(),
                "不能修改自己的状态");
        verify(enterpriseMemberMapper, never()).updateById(any(EnterpriseMember.class));
    }

    @Test
    void shouldRejectWhenPlainMemberModifiesStatus() {
        when(enterpriseMapper.selectById(10L)).thenReturn(enterprise(10L, "企业A", EnterpriseStatus.NORMAL));
        EnterpriseMember plainMember = member(1L, 10L, 7L);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(plainMember);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateMemberStatus(
                        7L, 10L, 8L, statusRequest(EnterpriseMemberStatus.DISABLED)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode(), "普通成员无权修改状态");
    }

    @Test
    void shouldReturnNotFoundWhenStatusUpdateTargetsMissingEnterprise() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> enterpriseService.updateMemberStatus(
                        7L, 99L, 8L, statusRequest(EnterpriseMemberStatus.DISABLED)));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode(), "企业不存在应抛 NOT_FOUND");
        verify(enterpriseMemberMapper, never()).selectOne(any());
    }

    private EnterpriseMemberStatusUpdateRequest statusRequest(EnterpriseMemberStatus status) {
        EnterpriseMemberStatusUpdateRequest request = new EnterpriseMemberStatusUpdateRequest();
        request.setStatus(status);
        return request;
    }

    private Wrapper<EnterpriseMember> anyMemberWrapper() {
        return any();
    }

    private EnterpriseCreateRequest request(String name) {
        EnterpriseCreateRequest request = new EnterpriseCreateRequest();
        request.setName(name);
        return request;
    }

    private EnterpriseUpdateRequest updateRequest(String name) {
        EnterpriseUpdateRequest request = new EnterpriseUpdateRequest();
        request.setName(name);
        return request;
    }

    private EnterpriseMember member(Long id, Long enterpriseId, Long userId) {
        EnterpriseMember member = new EnterpriseMember();
        member.setId(id);
        member.setEnterpriseId(enterpriseId);
        member.setUserId(userId);
        member.setMemberRole(EnterpriseMemberRole.MEMBER);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        member.setJoinedAt(NOW);
        member.setCreatedAt(NOW);
        member.setUpdatedAt(NOW);
        return member;
    }

    private Enterprise enterprise(Long id, String name, EnterpriseStatus status) {
        Enterprise enterprise = new Enterprise();
        enterprise.setId(id);
        enterprise.setName(name);
        enterprise.setSlug("slug-" + id);
        enterprise.setStatus(status);
        enterprise.setCreatedAt(NOW);
        enterprise.setUpdatedAt(NOW);
        return enterprise;
    }

    private User user(Long id, String email, String nickname) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setNickname(nickname);
        user.setStatus(UserStatus.NORMAL);
        user.setCreatedAt(NOW);
        user.setUpdatedAt(NOW);
        return user;
    }
}
