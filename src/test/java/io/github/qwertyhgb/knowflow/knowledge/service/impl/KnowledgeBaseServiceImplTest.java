package io.github.qwertyhgb.knowflow.knowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseMembershipChecker;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseCreateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseMemberAddRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseMemberRoleUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBase;
import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBaseMember;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseAccessMode;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseMemberRole;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseStatus;
import io.github.qwertyhgb.knowflow.knowledge.mapper.KnowledgeBaseMapper;
import io.github.qwertyhgb.knowflow.knowledge.mapper.KnowledgeBaseMemberMapper;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseMemberVO;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseVO;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");

    @Mock
    private EnterpriseMapper enterpriseMapper;

    @Mock
    private EnterpriseMemberMapper enterpriseMemberMapper;

    @Mock
    private EnterpriseRoleMapper enterpriseRoleMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private KnowledgeBaseMemberMapper knowledgeBaseMemberMapper;

    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, EnterpriseMember.class);
        TableInfoHelper.initTableInfo(assistant, EnterpriseRole.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseMember.class);
    }

    @BeforeEach
    void setUp() {
        // 使用真实 checker（内部走 enterpriseMapper / enterpriseMemberMapper 两个 mock），
        // 保持既有 requireEnterprise / requireActiveMember 桩的语义不变。
        EnterpriseMembershipChecker membershipChecker =
                new EnterpriseMembershipChecker(enterpriseMapper, enterpriseMemberMapper);
        knowledgeBaseService = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                knowledgeBaseMemberMapper,
                membershipChecker,
                enterpriseMemberMapper,
                enterpriseRoleMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldCreateKnowledgeBaseAsActiveMember() {
        allowActiveMember();
        when(knowledgeBaseMapper.insert(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase knowledgeBase = invocation.getArgument(0);
            knowledgeBase.setId(500L);
            return 1;
        });

        KnowledgeBaseCreateRequest request = request("  企业手册  ", "团队知识沉淀", null);
        KnowledgeBase result = knowledgeBaseService.createKnowledgeBase(7L, 10L, request);

        // 知识库插入字段断言：名称去空白、accessMode 默认 PRIVATE、status NORMAL、时间=Clock 冻结值。
        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).insert(kbCaptor.capture());
        KnowledgeBase saved = kbCaptor.getValue();
        assertEquals(10L, saved.getEnterpriseId());
        assertEquals("企业手册", saved.getName(), "名称应去除首尾空白");
        assertEquals("团队知识沉淀", saved.getDescription());
        assertEquals(KnowledgeBaseAccessMode.PRIVATE, saved.getAccessMode(), "未显式传 accessMode 应默认 PRIVATE");
        assertEquals(7L, saved.getOwnerUserId());
        assertEquals(KnowledgeBaseStatus.NORMAL, saved.getStatus());
        assertEquals(NOW, saved.getCreatedAt());
        assertEquals(NOW, saved.getUpdatedAt());

        // 创建者成员记录断言：同一事务内写入 ADMIN。
        ArgumentCaptor<KnowledgeBaseMember> memberCaptor = ArgumentCaptor.forClass(KnowledgeBaseMember.class);
        verify(knowledgeBaseMemberMapper).insert(memberCaptor.capture());
        KnowledgeBaseMember ownerMember = memberCaptor.getValue();
        assertEquals(500L, ownerMember.getKnowledgeBaseId(), "成员记录应关联刚插入的知识库 ID");
        assertEquals(10L, ownerMember.getEnterpriseId());
        assertEquals(7L, ownerMember.getUserId());
        assertEquals(KnowledgeBaseMemberRole.ADMIN, ownerMember.getMemberRole(), "创建者应自动成为 ADMIN");
        assertEquals(NOW, ownerMember.getCreatedAt());
        assertEquals(NOW, ownerMember.getUpdatedAt());

        assertSame(saved, result);
        assertEquals(500L, result.getId());
    }

    @Test
    void shouldHonorExplicitPublicAccessMode() {
        allowActiveMember();
        when(knowledgeBaseMapper.insert(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase knowledgeBase = invocation.getArgument(0);
            knowledgeBase.setId(500L);
            return 1;
        });

        KnowledgeBaseCreateRequest request = request("公开手册", null, KnowledgeBaseAccessMode.PUBLIC);
        KnowledgeBase result = knowledgeBaseService.createKnowledgeBase(7L, 10L, request);

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).insert(captor.capture());
        assertEquals(KnowledgeBaseAccessMode.PUBLIC, captor.getValue().getAccessMode(), "显式传 PUBLIC 应生效");
    }

    @Test
    void shouldRejectMissingEnterpriseBeforePermissionCheck() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.createKnowledgeBase(7L, 99L, request("手册", null, null)));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(enterpriseMemberMapper, never()).exists(any());
        verify(knowledgeBaseMapper, never()).insert(any(KnowledgeBase.class));
        verify(knowledgeBaseMemberMapper, never()).insert(any(KnowledgeBaseMember.class));
    }

    @Test
    void shouldRejectNonMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.createKnowledgeBase(7L, 10L, request("手册", null, null)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(knowledgeBaseMapper, never()).insert(any(KnowledgeBase.class));
        verify(knowledgeBaseMemberMapper, never()).insert(any(KnowledgeBaseMember.class));
    }

    @Test
    void shouldRejectDisabledMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        // requireActiveMember 内部用 exists 判断「成员存在且 status=NORMAL」；false 即视为非正常成员。
        when(enterpriseMemberMapper.exists(any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.createKnowledgeBase(7L, 10L, request("手册", null, null)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(knowledgeBaseMapper, never()).insert(any(KnowledgeBase.class));
        verify(knowledgeBaseMemberMapper, never()).insert(any(KnowledgeBaseMember.class));
    }

    // ==================== 列表 listVisibleKnowledgeBases ====================

    @Test
    void shouldListAllNormalKnowledgeBasesWithMyRoleMerged() {
        allowActiveMember();
        // 企业下有 2 个 NORMAL 知识库；当前用户是其中 1 个的成员（ADMIN），另一个非成员。
        List<KnowledgeBase> knowledgeBases = List.of(knowledgeBase(101L, KnowledgeBaseAccessMode.PUBLIC),
                knowledgeBase(102L, KnowledgeBaseAccessMode.PRIVATE));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(knowledgeBases);
        when(knowledgeBaseMemberMapper.selectList(any()))
                .thenReturn(List.of(member(102L, KnowledgeBaseMemberRole.EDITOR)));

        List<KnowledgeBaseVO> result = knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L);

        assertEquals(2, result.size());
        // 保持数据库返回顺序（按 createdAt 倒序），逐条标注 myRole。
        assertEquals(101L, result.get(0).getId());
        assertNull(result.get(0).getMyRole(), "PUBLIC 且非成员 → myRole 应为 null");
        assertEquals(102L, result.get(1).getId());
        assertEquals(KnowledgeBaseMemberRole.EDITOR, result.get(1).getMyRole(), "成员 → myRole 取成员记录角色");
    }

    @Test
    void shouldListWithMyRoleWhenPrivateMember() {
        allowActiveMember();
        List<KnowledgeBase> knowledgeBases = List.of(knowledgeBase(102L, KnowledgeBaseAccessMode.PRIVATE));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(knowledgeBases);
        when(knowledgeBaseMemberMapper.selectList(any()))
                .thenReturn(List.of(member(102L, KnowledgeBaseMemberRole.ADMIN)));

        List<KnowledgeBaseVO> result = knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L);

        assertEquals(1, result.size());
        assertEquals(102L, result.get(0).getId());
        assertEquals(KnowledgeBaseMemberRole.ADMIN, result.get(0).getMyRole());
    }

    @Test
    void shouldExcludePrivateKnowledgeBaseForNonMemberInList() {
        allowActiveMember();
        // 企业下有 PUBLIC(101, 非成员) 与 PRIVATE(102, 非成员) 两个 NORMAL 知识库；
        // 当前用户成员记录为空。PRIVATE 且非成员的知识库不应出现在可见列表中，避免泄露其存在性。
        List<KnowledgeBase> knowledgeBases = List.of(
                knowledgeBase(101L, KnowledgeBaseAccessMode.PUBLIC),
                knowledgeBase(102L, KnowledgeBaseAccessMode.PRIVATE));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(knowledgeBases);
        when(knowledgeBaseMemberMapper.selectList(any())).thenReturn(List.of());

        List<KnowledgeBaseVO> result = knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L);

        // 只保留 PUBLIC 的 101；PRIVATE 的 102 被过滤（保持创建时间倒序稳定排序）。
        assertEquals(1, result.size());
        assertEquals(101L, result.get(0).getId());
        assertNull(result.get(0).getMyRole(), "PUBLIC 非成员 → myRole 应为 null");
    }

    @Test
    void shouldReturnEmptyListWhenNoKnowledgeBases() {
        allowActiveMember();
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of());

        List<KnowledgeBaseVO> result = knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L);

        assertTrue(result.isEmpty());
        // 无知识库时不查询成员记录。
        verify(knowledgeBaseMemberMapper, never()).selectList(any());
    }

    @Test
    void shouldRejectListWhenEnterpriseMissing() {
        when(enterpriseMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.listVisibleKnowledgeBases(7L, 99L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(enterpriseMemberMapper, never()).exists(any());
        verify(knowledgeBaseMapper, never()).selectList(any());
    }

    @Test
    void shouldRejectListForNonMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(knowledgeBaseMapper, never()).selectList(any());
    }

    @Test
    void shouldListOnlyNormalKnowledgeBasesInCurrentEnterprise() {
        allowActiveMember();
        // 返回一个非空列表，避免命中最前端的空列表短路分支被本用例覆盖之外（此处仅验证查询条件）。
        when(knowledgeBaseMapper.selectList(any()))
                .thenReturn(List.of(knowledgeBase(101L, KnowledgeBaseAccessMode.PUBLIC)));
        when(knowledgeBaseMemberMapper.selectList(any())).thenReturn(List.of());

        knowledgeBaseService.listVisibleKnowledgeBases(7L, 10L);

        // 列表查询必须限定当前企业 + 仅 NORMAL 状态。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBase>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(knowledgeBaseMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("enterprise_id"), "列表查询必须限定当前企业");
        assertTrue(sql.contains("status"), "列表查询必须过滤 NORMAL 状态");
    }

    // ==================== 详情 getKnowledgeBase ====================

    @Test
    void shouldReturnPublicKnowledgeBaseForNonMember() {
        allowActiveMember();
        KnowledgeBase kb = knowledgeBase(101L, KnowledgeBaseAccessMode.PUBLIC);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb);
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(null);

        KnowledgeBaseVO result = knowledgeBaseService.getKnowledgeBase(7L, 10L, 101L);

        assertEquals(101L, result.getId());
        assertNull(result.getMyRole(), "PUBLIC 非成员 → myRole 应为 null");
    }

    @Test
    void shouldReturnMemberRoleForMember() {
        allowActiveMember();
        KnowledgeBase kb = knowledgeBase(102L, KnowledgeBaseAccessMode.PRIVATE);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb);
        when(knowledgeBaseMemberMapper.selectOne(any()))
                .thenReturn(member(102L, KnowledgeBaseMemberRole.VIEWER));

        KnowledgeBaseVO result = knowledgeBaseService.getKnowledgeBase(7L, 10L, 102L);

        assertEquals(KnowledgeBaseMemberRole.VIEWER, result.getMyRole(), "成员 → myRole 取成员记录角色");
    }

    @Test
    void shouldReturnNotFoundForPrivateNonMember() {
        allowActiveMember();
        KnowledgeBase kb = knowledgeBase(102L, KnowledgeBaseAccessMode.PRIVATE);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb);
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.getKnowledgeBase(7L, 10L, 102L));

        // 隐私保护：PRIVATE 非成员 → 404 而非 403，不泄露知识库存在性。
        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void shouldReturnNotFoundWhenKnowledgeBaseMissing() {
        allowActiveMember();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.getKnowledgeBase(7L, 10L, 999L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).selectOne(any());
    }

    @Test
    void shouldReturnNotFoundWhenKnowledgeBaseDisabled() {
        allowActiveMember();
        KnowledgeBase disabled = knowledgeBase(102L, KnowledgeBaseAccessMode.PUBLIC);
        disabled.setStatus(KnowledgeBaseStatus.DISABLED);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(disabled);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.getKnowledgeBase(7L, 10L, 102L));

        // 禁用按不存在处理，不泄露状态信息。
        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).selectOne(any());
    }

    @Test
    void shouldScopeDetailQueryToEnterprise() {
        allowActiveMember();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> knowledgeBaseService.getKnowledgeBase(7L, 10L, 101L));

        // 详情查询必须用「id + enterpriseId」组合定位，防止跨企业读取。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBase>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(knowledgeBaseMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("id") && sql.contains("enterprise_id"),
                "详情查询必须同时限定 knowledge_base_id 与 enterprise_id");
    }

    // ==================== 添加成员 addKnowledgeBaseMember ====================

    @Test
    void shouldAddMemberAsResourceAdmin() {
        allowResourceAdmin();
        // exists 两次调用序列：第 1 次是 requireKnowledgeBaseManager 的资源级 ADMIN 校验（true），
        // 第 2 次是添加成员的查重校验（false → 该目标用户不存在成员记录，允许插入）。
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(true, false);
        when(knowledgeBaseMemberMapper.insert(any(KnowledgeBaseMember.class))).thenAnswer(invocation -> {
            KnowledgeBaseMember member = invocation.getArgument(0);
            member.setId(300L);
            return 1;
        });

        KnowledgeBaseMemberVO result = knowledgeBaseService.addKnowledgeBaseMember(
                7L, 10L, 100L, addRequest(8L, KnowledgeBaseMemberRole.EDITOR));

        ArgumentCaptor<KnowledgeBaseMember> captor = ArgumentCaptor.forClass(KnowledgeBaseMember.class);
        verify(knowledgeBaseMemberMapper).insert(captor.capture());
        KnowledgeBaseMember saved = captor.getValue();
        assertEquals(100L, saved.getKnowledgeBaseId());
        assertEquals(10L, saved.getEnterpriseId());
        assertEquals(8L, saved.getUserId());
        assertEquals(KnowledgeBaseMemberRole.EDITOR, saved.getMemberRole());
        assertEquals(NOW, saved.getCreatedAt());
        assertEquals(NOW, saved.getUpdatedAt());
        assertEquals(300L, result.getId());
    }

    @Test
    void shouldAddOwnerMember() {
        allowResourceAdmin();
        // 同上：第 1 次 exists = 资源级 ADMIN 校验（true），第 2 次 = 查重（false）。
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(true, false);
        when(knowledgeBaseMemberMapper.insert(any(KnowledgeBaseMember.class))).thenAnswer(invocation -> {
            KnowledgeBaseMember member = invocation.getArgument(0);
            member.setId(300L);
            return 1;
        });

        knowledgeBaseService.addKnowledgeBaseMember(7L, 10L, 100L,
                addRequest(8L, KnowledgeBaseMemberRole.ADMIN));

        verify(knowledgeBaseMemberMapper).insert(any(KnowledgeBaseMember.class));
    }

    @Test
    void shouldRejectAddMemberWhenTargetNotEnterpriseMember() {
        allowResourceAdmin();
        // enterpriseMemberMapper.exists 序列：第 1 次是 requireManager 的操作者(7)正常成员校验（true），
        // 第 2 次是目标用户(8)的企业成员校验（false → 目标非该企业正常成员 → 403，复合外键兜底前的友好错误）。
        when(enterpriseMemberMapper.exists(any())).thenReturn(true, false);
        // 资源级 ADMIN 校验通过即可（true）；重复查重在目标校验失败后不会执行。
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.addKnowledgeBaseMember(
                        7L, 10L, 100L, addRequest(8L, KnowledgeBaseMemberRole.VIEWER)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).insert(any(KnowledgeBaseMember.class));
    }

    @Test
    void shouldRejectDuplicateMember() {
        allowResourceAdmin();
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.addKnowledgeBaseMember(
                        7L, 10L, 100L, addRequest(8L, KnowledgeBaseMemberRole.VIEWER)));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_MEMBER_ALREADY_EXISTS, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).insert(any(KnowledgeBaseMember.class));
    }

    @Test
    void shouldRejectAddMemberWhenKnowledgeBaseNotFound() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.addKnowledgeBaseMember(
                        7L, 10L, 100L, addRequest(8L, KnowledgeBaseMemberRole.VIEWER)));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).insert(any(KnowledgeBaseMember.class));
    }

    @Test
    void shouldRejectAddMemberWhenKnowledgeBaseDisabled() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
        KnowledgeBase disabled = knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE);
        disabled.setStatus(KnowledgeBaseStatus.DISABLED);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(disabled);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.addKnowledgeBaseMember(
                        7L, 10L, 100L, addRequest(8L, KnowledgeBaseMemberRole.VIEWER)));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).insert(any(KnowledgeBaseMember.class));
    }

    @Test
    void shouldRejectAddMemberWhenOperatorIsPlainMember() {
        allowPlainMemberAsManagerCandidate();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE));
        // 非知识库 ADMIN，且企业角色是 MEMBER（非管理角色）→ 无管理权限，403。
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(false);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(enterpriseMember(7L, 100L));
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("MEMBER"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.addKnowledgeBaseMember(
                        7L, 10L, 100L, addRequest(8L, KnowledgeBaseMemberRole.VIEWER)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).insert(any(KnowledgeBaseMember.class));
    }

    // ==================== 改角色 updateKnowledgeBaseMemberRole ====================

    @Test
    void shouldUpdateMemberRole() {
        allowResourceAdmin();
        KnowledgeBaseMember target = memberWithUser(100L, 8L, KnowledgeBaseMemberRole.VIEWER);
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(target);
        when(knowledgeBaseMemberMapper.update(any(), any(Wrapper.class)))
                .thenReturn(1);

        KnowledgeBaseMemberVO result = knowledgeBaseService.updateKnowledgeBaseMemberRole(
                7L, 10L, 100L, 8L, roleUpdateRequest(KnowledgeBaseMemberRole.ADMIN));

        assertEquals(KnowledgeBaseMemberRole.ADMIN, result.getMemberRole());
        assertEquals(NOW, result.getCreatedAt());
    }

    @Test
    void shouldRejectUpdateMemberRoleWhenTargetNotMember() {
        allowResourceAdmin();
        when(knowledgeBaseMemberMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.updateKnowledgeBaseMemberRole(
                        7L, 10L, 100L, 8L, roleUpdateRequest(KnowledgeBaseMemberRole.ADMIN)));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_MEMBER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void shouldRejectUpdatingOwnRole() {
        allowResourceAdmin();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.updateKnowledgeBaseMemberRole(
                        7L, 10L, 100L, 7L, roleUpdateRequest(KnowledgeBaseMemberRole.VIEWER)));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_SELF_OPERATION_NOT_ALLOWED, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).update(any(), any());
    }

    @Test
    void shouldRejectUpdateMemberRoleWhenOperatorIsPlainMember() {
        allowPlainMemberAsManagerCandidate();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE));
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(false);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(enterpriseMember(7L, 100L));
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("MEMBER"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.updateKnowledgeBaseMemberRole(
                        7L, 10L, 100L, 8L, roleUpdateRequest(KnowledgeBaseMemberRole.ADMIN)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    // ==================== 移除成员 removeKnowledgeBaseMember ====================

    @Test
    void shouldRemoveMember() {
        allowResourceAdmin();
        when(knowledgeBaseMemberMapper.delete(any())).thenReturn(1);

        knowledgeBaseService.removeKnowledgeBaseMember(7L, 10L, 100L, 8L);

        verify(knowledgeBaseMemberMapper).delete(any());
    }

    @Test
    void shouldRejectRemoveMemberWhenTargetNotMember() {
        allowResourceAdmin();
        when(knowledgeBaseMemberMapper.delete(any())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.removeKnowledgeBaseMember(7L, 10L, 100L, 8L));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_MEMBER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void shouldRejectRemovingOwnMembership() {
        allowResourceAdmin();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.removeKnowledgeBaseMember(7L, 10L, 100L, 7L));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_SELF_OPERATION_NOT_ALLOWED, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).delete(any());
    }

    @Test
    void shouldRejectRemoveMemberWhenOperatorIsPlainMember() {
        allowPlainMemberAsManagerCandidate();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE));
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(false);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(enterpriseMember(7L, 100L));
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("MEMBER"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.removeKnowledgeBaseMember(7L, 10L, 100L, 8L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(knowledgeBaseMemberMapper, never()).delete(any());
    }

    // ==================== 更新 updateKnowledgeBase ====================

    @Test
    void shouldUpdateKnowledgeBaseAsResourceAdmin() {
        allowResourceAdmin();
        when(knowledgeBaseMapper.update(any(), any(Wrapper.class)))
                .thenReturn(1);

        KnowledgeBaseVO result = knowledgeBaseService.updateKnowledgeBase(
                7L, 10L, 100L, updateRequest("  平台手册  ", "新描述", KnowledgeBaseAccessMode.PUBLIC));

        assertEquals(100L, result.getId());
        assertEquals("平台手册", result.getName(), "名称应去除首尾空白");
        assertEquals("新描述", result.getDescription());
        assertEquals(KnowledgeBaseAccessMode.PUBLIC, result.getAccessMode());

        // KnowledgeBaseVO 不暴露 updatedAt；通过捕获的更新 wrapper 校验 updated_at 被写为 Clock 冻结值。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBase>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(knowledgeBaseMapper).update(any(), wrapperCaptor.capture());
        String updateSql = wrapperCaptor.getValue().getSqlSet();
        assertTrue(updateSql.contains("updated_at"), "更新必须刷新 updated_at");
    }

    @Test
    void shouldRejectUpdateWhenOperatorIsPlainMember() {
        allowPlainMemberAsManagerCandidate();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE));
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(false);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(enterpriseMember(7L, 100L));
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("MEMBER"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.updateKnowledgeBase(
                        7L, 10L, 100L, updateRequest("手册", null, KnowledgeBaseAccessMode.PRIVATE)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void shouldRejectUpdateWhenKnowledgeBaseNotFound() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.updateKnowledgeBase(
                        7L, 10L, 100L, updateRequest("手册", null, KnowledgeBaseAccessMode.PRIVATE)));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, exception.getErrorCode());
    }

    // ==================== 删除 deleteKnowledgeBase ====================

    @Test
    void shouldDeleteKnowledgeBaseCascadeMembersInOrder() {
        allowResourceAdmin();
        when(knowledgeBaseMapper.delete(any())).thenReturn(1);

        knowledgeBaseService.deleteKnowledgeBase(7L, 10L, 100L);

        // 先删成员记录，再删知识库（先子后父；外键无 CASCADE，必须手动级联）。
        verify(knowledgeBaseMemberMapper).delete(any());
        verify(knowledgeBaseMapper).delete(any());
    }

    @Test
    void shouldRejectDeleteWhenOperatorIsPlainMember() {
        allowPlainMemberAsManagerCandidate();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE));
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(false);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(enterpriseMember(7L, 100L));
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("MEMBER"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.deleteKnowledgeBase(7L, 10L, 100L));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(knowledgeBaseMapper, never()).delete(any());
    }

    // ==================== 状态 updateKnowledgeBaseStatus ====================

    @Test
    void shouldDisableKnowledgeBase() {
        allowResourceAdmin();
        when(knowledgeBaseMapper.update(any(), any(Wrapper.class)))
                .thenReturn(1);

        KnowledgeBaseVO result = knowledgeBaseService.updateKnowledgeBaseStatus(
                7L, 10L, 100L, statusRequest(KnowledgeBaseStatus.DISABLED));

        assertEquals(KnowledgeBaseStatus.DISABLED, result.getStatus());

        // KnowledgeBaseVO 不暴露 updatedAt；通过捕获的更新 wrapper 校验 updated_at 被刷新。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBase>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(knowledgeBaseMapper).update(any(), wrapperCaptor.capture());
        String updateSql = wrapperCaptor.getValue().getSqlSet();
        assertTrue(updateSql.contains("updated_at"), "禁用必须刷新 updated_at");
    }

    @Test
    void shouldTreatUnchangedStatusAsIdempotent() {
        allowResourceAdmin();
        KnowledgeBase normal = knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(normal);
        // 同状态（已是 NORMAL）→ 直接返回，不触发 update。
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(false);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(enterpriseMember(7L, 100L));
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("ADMIN"));

        KnowledgeBaseVO result = knowledgeBaseService.updateKnowledgeBaseStatus(
                7L, 10L, 100L, statusRequest(KnowledgeBaseStatus.NORMAL));

        assertEquals(KnowledgeBaseStatus.NORMAL, result.getStatus());
        verify(knowledgeBaseMapper, never()).update(any(), any());
    }

    @Test
    void shouldRejectStatusUpdateWhenOperatorIsPlainMember() {
        allowPlainMemberAsManagerCandidate();
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE));
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(false);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(enterpriseMember(7L, 100L));
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("MEMBER"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.updateKnowledgeBaseStatus(
                        7L, 10L, 100L, statusRequest(KnowledgeBaseStatus.DISABLED)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    // ==================== 企业级管理员兜底（两级取或） ====================

    @Test
    void shouldAllowEnterpriseAdminToManageWithoutMembership() {
        // 操作者是企业 OWNER/ADMIN 但非知识库成员 → 管理的两级管理员取或应通过企业级。
        allowEnterpriseManager();
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(false);
        when(enterpriseMemberMapper.selectOne(any())).thenReturn(enterpriseMember(7L, 100L));
        when(enterpriseRoleMapper.selectById(100L)).thenReturn(role("OWNER"));
        when(knowledgeBaseMapper.update(any(), any(Wrapper.class)))
                .thenReturn(1);

        KnowledgeBaseVO result = knowledgeBaseService.updateKnowledgeBase(
                7L, 10L, 100L, updateRequest("企业级更新", null, KnowledgeBaseAccessMode.PUBLIC));

        // 不应抛 403（企业级 OWNER 可管理企业内所有知识库）。
        assertEquals(100L, result.getId());
        verify(knowledgeBaseMapper).update(any(), any());
    }

    // ==================== 辅助方法 ====================

    /** 仅需企业正常成员身份即可（创建/列表/详情等基础能力场景）。 */
    private void allowActiveMember() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
    }

    /** 操作者是知识库 ADMIN 成员：企业校验通过 + 该知识库存在且 NORMAL 时即可管理。 */
    private void allowResourceAdmin() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE));
        when(knowledgeBaseMemberMapper.exists(any())).thenReturn(true);
    }

    /** 操作者是企业正常成员，但既非知识库 ADMIN 也非企业管理角色的候选（用于验证 403）。 */
    private void allowPlainMemberAsManagerCandidate() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
    }

    /** 操作者是企业 OWNER/ADMIN：企业校验通过 + 该知识库存在且 NORMAL。 */
    private void allowEnterpriseManager() {
        when(enterpriseMapper.selectById(10L)).thenReturn(new Enterprise());
        when(enterpriseMemberMapper.exists(any())).thenReturn(true);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase(100L, KnowledgeBaseAccessMode.PRIVATE));
    }

    private EnterpriseMember enterpriseMember(Long userId, Long roleId) {
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(10L);
        member.setUserId(userId);
        member.setRoleId(roleId);
        return member;
    }

    private EnterpriseRole role(String code) {
        EnterpriseRole role = new EnterpriseRole();
        role.setId(100L);
        role.setEnterpriseId(10L);
        role.setCode(code);
        role.setStatus(EnterpriseRoleStatus.NORMAL);
        return role;
    }

    private KnowledgeBaseMember memberWithUser(Long knowledgeBaseId, Long userId, KnowledgeBaseMemberRole role) {
        KnowledgeBaseMember member = new KnowledgeBaseMember();
        member.setId(200L);
        member.setKnowledgeBaseId(knowledgeBaseId);
        member.setEnterpriseId(10L);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setCreatedAt(NOW);
        member.setUpdatedAt(NOW);
        return member;
    }

    private KnowledgeBaseMemberAddRequest addRequest(Long userId, KnowledgeBaseMemberRole role) {
        KnowledgeBaseMemberAddRequest request = new KnowledgeBaseMemberAddRequest();
        request.setUserId(userId);
        request.setMemberRole(role);
        return request;
    }

    private KnowledgeBaseMemberRoleUpdateRequest roleUpdateRequest(KnowledgeBaseMemberRole role) {
        KnowledgeBaseMemberRoleUpdateRequest request = new KnowledgeBaseMemberRoleUpdateRequest();
        request.setMemberRole(role);
        return request;
    }

    private KnowledgeBaseUpdateRequest updateRequest(String name, String description,
                                                     KnowledgeBaseAccessMode accessMode) {
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName(name);
        request.setDescription(description);
        request.setAccessMode(accessMode);
        return request;
    }

    private KnowledgeBaseStatusUpdateRequest statusRequest(KnowledgeBaseStatus status) {
        KnowledgeBaseStatusUpdateRequest request = new KnowledgeBaseStatusUpdateRequest();
        request.setStatus(status);
        return request;
    }

    private KnowledgeBase knowledgeBase(Long id, KnowledgeBaseAccessMode accessMode) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setEnterpriseId(10L);
        knowledgeBase.setName("知识库" + id);
        knowledgeBase.setAccessMode(accessMode);
        knowledgeBase.setOwnerUserId(7L);
        knowledgeBase.setStatus(KnowledgeBaseStatus.NORMAL);
        knowledgeBase.setCreatedAt(NOW);
        knowledgeBase.setUpdatedAt(NOW);
        return knowledgeBase;
    }

    private KnowledgeBaseMember member(Long knowledgeBaseId, KnowledgeBaseMemberRole role) {
        KnowledgeBaseMember member = new KnowledgeBaseMember();
        member.setKnowledgeBaseId(knowledgeBaseId);
        member.setEnterpriseId(10L);
        member.setUserId(7L);
        member.setMemberRole(role);
        return member;
    }

    private KnowledgeBaseCreateRequest request(String name, String description,
                                               KnowledgeBaseAccessMode accessMode) {
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName(name);
        request.setDescription(description);
        request.setAccessMode(accessMode);
        return request;
    }
}
