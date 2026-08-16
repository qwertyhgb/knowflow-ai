package io.github.qwertyhgb.knowflow.enterprise.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseMemberStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseUpdateRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseMembershipChecker;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseService;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseMemberVO;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseVO;
import io.github.qwertyhgb.knowflow.user.entity.User;
import io.github.qwertyhgb.knowflow.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 企业业务服务实现。
 *
 * <p><strong>创建企业流程：</strong></p>
 * <ol>
 *   <li>组装 {@link Enterprise}：名称去首尾空白、状态 {@code NORMAL}、
 *       自动生成唯一标识 {@code slug}、统一 UTC 时间。</li>
 *   <li>插入企业记录。</li>
 *   <li>组装 {@link EnterpriseMember}：把当前登录用户设为该企业的
 *       {@code OWNER} 成员，状态 {@code NORMAL}，记录加入时间。</li>
 *   <li>插入成员关系记录。</li>
 * </ol>
 *
 * <p><strong>事务边界：</strong>「插入企业」与「插入所有者成员」必须一起成功或一起失败，
 * 因此 {@link #createEnterprise(Long, EnterpriseCreateRequest)} 标注
 * {@link Transactional}：任一插入失败时整体回滚，不会出现“有企业却没有所有者”的脏状态。</p>
 */
@Slf4j
@Service
public class EnterpriseServiceImpl implements EnterpriseService {

    private final EnterpriseMapper enterpriseMapper;

    private final EnterpriseMemberMapper enterpriseMemberMapper;

    /** 企业角色表 Mapper：创建企业时预置内置角色、按 roleId 解析角色编码。 */
    private final EnterpriseRoleMapper enterpriseRoleMapper;

    /**
     * 可注入的 UTC 时钟，用于生成 {@code createdAt} / {@code updatedAt} / {@code joinedAt}：
     * 生产注入 {@code Clock.systemUTC()}，测试注入 {@code Clock.fixed(...)} 冻结时间。
     */
    private final Clock clock;

    /** 用户表 Mapper，用于成员列表关联查询邮箱/昵称。 */
    private final UserMapper userMapper;

    /** 企业成员身份校验（企业存在 → 404；正常成员 → 403），与邀请/部门模块共用。 */
    private final EnterpriseMembershipChecker membershipChecker;

    public EnterpriseServiceImpl(EnterpriseMapper enterpriseMapper,
                                 EnterpriseMemberMapper enterpriseMemberMapper,
                                 EnterpriseRoleMapper enterpriseRoleMapper,
                                 UserMapper userMapper,
                                 EnterpriseMembershipChecker membershipChecker,
                                 Clock clock) {
        this.enterpriseMapper = enterpriseMapper;
        this.enterpriseMemberMapper = enterpriseMemberMapper;
        this.enterpriseRoleMapper = enterpriseRoleMapper;
        this.userMapper = userMapper;
        this.membershipChecker = membershipChecker;
        this.clock = clock;
    }

    @Override
    @Transactional
    public EnterpriseVO createEnterprise(Long userId, EnterpriseCreateRequest request) {
        String name = request.getName().strip();
        Instant now = clock.instant();

        Enterprise enterprise = new Enterprise();
        enterprise.setName(name);
        enterprise.setSlug(generateSlug());
        enterprise.setStatus(EnterpriseStatus.NORMAL);
        enterprise.setCreatedAt(now);
        enterprise.setUpdatedAt(now);
        enterpriseMapper.insert(enterprise);

        // 为新企业预置 V6 约定的 3 个内置角色（OWNER / ADMIN / MEMBER），
        // 与迁移时「为每个已有企业插入内置角色」的语义保持一致。
        insertBuiltinRoles(enterprise.getId(), now);

        // 创建者自动成为企业所有者（角色编码 OWNER），与企业同属一个事务。
        // 角色判断一律经 enterprise_role.code，V3 遗留的 member_role 列已退役（V7 删除）。
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(enterprise.getId());
        member.setUserId(userId);
        member.setRoleId(ownerRoleId(enterprise.getId()));
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        member.setJoinedAt(now);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        enterpriseMemberMapper.insert(member);

        // 日志只记录系统生成的标识（enterpriseId、userId），不记录企业名称等用户自由文本。
        log.info("event=enterprise_created enterpriseId={} userId={}", enterprise.getId(), userId);
        return EnterpriseVO.from(enterprise);
    }

    /** 为新企业插入 V6 约定的 3 个内置角色；编码与名称与 V6 迁移的种子数据一致。 */
    private void insertBuiltinRoles(Long enterpriseId, Instant now) {
        insertBuiltinRole(enterpriseId, "OWNER", "所有者", "企业所有者，拥有全部权限", now);
        insertBuiltinRole(enterpriseId, "ADMIN", "管理员", "可管理成员与大部分企业设置", now);
        insertBuiltinRole(enterpriseId, "MEMBER", "成员", "默认角色", now);
    }

    private void insertBuiltinRole(Long enterpriseId, String code, String name,
                                   String description, Instant now) {
        EnterpriseRole role = new EnterpriseRole();
        role.setEnterpriseId(enterpriseId);
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        role.setStatus(EnterpriseRoleStatus.NORMAL);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        enterpriseRoleMapper.insert(role);
    }

    /** 查询新企业 OWNER 内置角色的 ID（内置角色刚由本事务插入，必存在）。 */
    private Long ownerRoleId(Long enterpriseId) {
        EnterpriseRole ownerRole = enterpriseRoleMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseRole>()
                        .eq(EnterpriseRole::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseRole::getCode, "OWNER"));
        return ownerRole.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EnterpriseVO> listMyEnterprises(Long userId) {
        // 1. 只查该用户的正常成员关系，并按加入时间、关系 ID 排序，保证结果稳定。
        List<EnterpriseMember> members = enterpriseMemberMapper.selectList(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getUserId, userId)
                        .eq(EnterpriseMember::getStatus, EnterpriseMemberStatus.NORMAL)
                        .orderByAsc(EnterpriseMember::getJoinedAt)
                        .orderByAsc(EnterpriseMember::getId));
        if (members.isEmpty()) {
            // 用户未加入任何企业：直接返回空列表，避免后续空 IN 查询，也不返回 null。
            return List.of();
        }
        List<Long> enterpriseIds = members.stream()
                .map(EnterpriseMember::getEnterpriseId)
                .distinct()
                .toList();

        // 2. 批量查询避免 N+1，再按成员关系顺序组装；数据库 IN 查询本身不保证顺序。
        Map<Long, Enterprise> enterpriseMap = enterpriseMapper.selectByIds(enterpriseIds).stream()
                .collect(Collectors.toMap(Enterprise::getId, Function.identity()));
        return enterpriseIds.stream()
                .map(enterpriseMap::get)
                .filter(enterprise -> enterprise != null
                        && enterprise.getStatus() == EnterpriseStatus.NORMAL)
                .map(EnterpriseVO::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EnterpriseVO getEnterpriseDetail(Long userId, Long enterpriseId) {
        // 企业不存在 → 404；非正常成员 → 403。统一由 EnterpriseMembershipChecker 校验。
        Enterprise enterprise = membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);
        return EnterpriseVO.from(enterprise);
    }

    @Override
    @Transactional
    public EnterpriseVO updateEnterprise(Long userId, Long enterpriseId, EnterpriseUpdateRequest request) {
        // 1. 企业不存在直接返回 404；先校验资源存在再做权限判断，
        //    避免把「不存在」误报成「无权限」。
        Enterprise enterprise = membershipChecker.requireEnterprise(enterpriseId);

        // 2. 校验当前用户是该企业的正常成员（403）。
        //    管理权限（enterprise:update）由 Controller 的 @PreAuthorize 按权限码校验，
        //    此处只保证企业成员身份，不再判断 OWNER/ADMIN 角色。
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 3. 只更新允许变更的字段，避免把查询到的旧 slug/status/createdAt 回写，
        //    从而覆盖其他并发业务对这些字段的修改。
        String name = request.getName().strip();
        Instant updatedAt = clock.instant();
        Enterprise update = new Enterprise();
        update.setId(enterpriseId);
        update.setName(name);
        update.setUpdatedAt(updatedAt);
        if (enterpriseMapper.updateById(update) != 1) {
            // 权限校验后记录被并发删除时，不返回虚假的更新成功。
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        enterprise.setName(name);
        enterprise.setUpdatedAt(updatedAt);

        // 日志只记录系统生成的标识（enterpriseId、userId），不记录企业名称等用户自由文本。
        log.info("event=enterprise_updated enterpriseId={} userId={}", enterpriseId, userId);
        return EnterpriseVO.from(enterprise);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EnterpriseMemberVO> listMembers(Long userId, Long enterpriseId) {
        // 权限：企业存在（404）+ 当前用户是正常成员（403）。
        // 查看成员列表的权限（member:view）由 Controller 的 @PreAuthorize 校验，
        // 此处只保证成员身份，不再做角色限制。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 1. 查询该企业全部成员关系，按加入时间升序保证返回顺序稳定。
        List<EnterpriseMember> members = enterpriseMemberMapper.selectList(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .orderByAsc(EnterpriseMember::getJoinedAt)
                        .orderByAsc(EnterpriseMember::getId));

        // 2. 批量查询关联用户，避免 N+1：一次性取回所有成员的 email / nickname。
        List<Long> userIds = members.stream()
                .map(EnterpriseMember::getUserId)
                .distinct()
                .toList();
        Map<Long, User> userMap = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        // 3. 批量查询成员角色编码（roleId → enterprise_role.code），避免 N+1；
        //    角色记录不存在（异常情况）时编码为 null，不阻断列表返回。
        List<Long> roleIds = members.stream()
                .map(EnterpriseMember::getRoleId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, EnterpriseRole> roleMap = roleIds.isEmpty()
                ? Map.of()
                : enterpriseRoleMapper.selectBatchIds(roleIds).stream()
                        .collect(Collectors.toMap(EnterpriseRole::getId, Function.identity()));

        // 4. 组装 VO；若某 userId 在 sys_user 中已不存在，email/nickname 留空。
        return members.stream()
                .map(member -> {
                    EnterpriseRole role = member.getRoleId() == null ? null : roleMap.get(member.getRoleId());
                    return EnterpriseMemberVO.from(member,
                            role != null ? role.getCode() : null,
                            userMap.get(member.getUserId()));
                })
                .toList();
    }

    @Override
    @Transactional
    public void removeMember(Long userId, Long enterpriseId, Long targetUserId) {
        // 1. 企业必须存在：先校验资源存在再做权限判断，避免把「不存在」误报成「无权限」。
        membershipChecker.requireEnterprise(enterpriseId);

        // 2. 当前用户必须是该企业的正常成员（403）。
        //    移除成员的权限（member:remove）由 Controller 的 @PreAuthorize 校验，
        //    此处只保证成员身份，不再判断 OWNER/ADMIN 角色。
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 3. 不能移除自己：管理员可以踢人但不能把自己踢出企业——
        //    否则企业会失去所有者或变成只剩一个非 OWNER 的管理员，导致管理死锁。
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.SELF_REMOVE_NOT_ALLOWED);
        }

        // 4. 目标成员必须在企业中：按「企业 + 用户 ID」查找，不存在则返回 404；
        //    不区分「查无此人」和「该人不是本企业成员」，防止信息泄露。
        EnterpriseMember targetMember = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, targetUserId));
        if (targetMember == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // 5. OWNER 不能被移除：所有者是企业创建者，即使被禁用成员关系也保留历史痕迹；
        //    这相当于给 OWNER 上了最后的安全栓，防止误操作或恶意内鬼清空企业管理层。
        //    角色判断经 roleId 关联的 enterprise_role.code（V3 的 member_role 列已退役）。
        if (isOwnerRole(targetMember)) {
            throw new BusinessException(ErrorCode.CANNOT_REMOVE_OWNER);
        }

        // 6. 目标已被禁用时幂等成功，不重复刷新 updatedAt。
        if (targetMember.getStatus() == EnterpriseMemberStatus.DISABLED) {
            return;
        }

        // 7. 软删除：将 status 置为 DISABLED；保留唯一的成员关系记录，
        //    既能记录加入时间，也允许管理员以后通过状态接口恢复成员。
        Instant updatedAt = clock.instant();
        int updatedCount = enterpriseMemberMapper.update(null,
                new LambdaUpdateWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getId, targetMember.getId())
                        .set(EnterpriseMember::getStatus, EnterpriseMemberStatus.DISABLED)
                        .set(EnterpriseMember::getUpdatedAt, updatedAt));
        if (updatedCount != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        log.info("event=member_removed enterpriseId={} operatorId={} targetId={}",
                enterpriseId, userId, targetUserId);
    }

    @Override
    @Transactional
    public void leaveEnterprise(Long userId, Long enterpriseId) {
        // 1. 企业必须存在：保持「先资源后权限」的全模块校验顺序。
        membershipChecker.requireEnterprise(enterpriseId);

        // 2. 退出不需要管理员角色，只按「企业 + 当前用户 ID」查成员关系。
        EnterpriseMember member = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, userId));
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // 3. OWNER 不能主动退出，否则企业会失去所有者；这与管理员移除 OWNER 的语义不同。
        //    角色判断经 roleId 关联的 enterprise_role.code（V3 的 member_role 列已退役）。
        if (isOwnerRole(member)) {
            throw new BusinessException(ErrorCode.OWNER_CANNOT_LEAVE);
        }

        // 4. 已退出或已被移除的成员重复调用直接成功，不重复写入或刷新时间。
        if (member.getStatus() == EnterpriseMemberStatus.DISABLED) {
            return;
        }

        // 5. 软删除与管理员移除保持一致：保留加入记录，允许管理员恢复，
        //    并复用现有成员状态接口重新启用，而不是物理删除成员关系。
        Instant updatedAt = clock.instant();
        int updatedCount = enterpriseMemberMapper.update(null,
                new LambdaUpdateWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getId, member.getId())
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .set(EnterpriseMember::getStatus, EnterpriseMemberStatus.DISABLED)
                        .set(EnterpriseMember::getUpdatedAt, updatedAt));
        if (updatedCount != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        log.info("event=member_left enterpriseId={} userId={}", enterpriseId, userId);
    }

    @Override
    @Transactional
    public EnterpriseMemberVO updateMemberStatus(Long userId, Long enterpriseId, Long targetUserId,
                                                 EnterpriseMemberStatusUpdateRequest request) {
        // 1. 企业必须存在 + 当前用户是正常成员：与移除成员共用同一套前置校验。
        //    修改成员状态的权限（member:status）由 Controller 的 @PreAuthorize 校验，
        //    此处只保证企业成员身份，不再判断 OWNER/ADMIN 角色。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 目标成员必须在企业中（404，不泄露存在性）。
        EnterpriseMember targetMember = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, targetUserId));
        if (targetMember == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // 3. 目标为 OWNER 时拒绝：所有者状态不可修改（禁用 OWNER 等价于移除所有者）。
        //    角色判断经 roleId 关联的 enterprise_role.code（V3 的 member_role 列已退役）。
        if (isOwnerRole(targetMember)) {
            throw new BusinessException(ErrorCode.CANNOT_REMOVE_OWNER);
        }

        // 4. 目标是自己时拒绝：管理员不能禁用自己，否则企业失去管理出口
        //    （启用自己无意义——能执行操作说明自己已是 NORMAL）。
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.SELF_REMOVE_NOT_ALLOWED);
        }

        // 5. 幂等：请求状态与当前状态相同直接返回，不触发更新、不刷新 updatedAt。
        EnterpriseMemberStatus targetStatus = request.getStatus();
        if (targetMember.getStatus() == targetStatus) {
            return toMemberVO(targetMember);
        }

        // 6. 更新状态与更新时间；失败（并发删除等异常情况）时返回 404，
        //    与 updateEnterprise 的防御性写法保持一致。
        Instant updatedAt = clock.instant();
        EnterpriseMember update = new EnterpriseMember();
        update.setId(targetMember.getId());
        update.setStatus(targetStatus);
        update.setUpdatedAt(updatedAt);
        if (enterpriseMemberMapper.updateById(update) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // 内存实体同步后组装响应（避免重新查库）。
        targetMember.setStatus(targetStatus);
        targetMember.setUpdatedAt(updatedAt);

        // 日志只记录枚举名（白名单值）与系统标识，不记录用户自由文本。
        log.info("event=member_status_updated enterpriseId={} operatorId={} targetId={} newStatus={}",
                enterpriseId, userId, targetUserId, targetStatus);
        return toMemberVO(targetMember);
    }

    /**
     * 组装成员视图：关联目标用户的公开信息（邮箱/昵称）与成员角色编码；
     * 用户记录不存在（异常情况）时邮箱/昵称留空，与成员列表行为一致。
     */
    private EnterpriseMemberVO toMemberVO(EnterpriseMember member) {
        User user = userMapper.selectById(member.getUserId());
        return EnterpriseMemberVO.from(member, roleCodeOf(member.getRoleId()), user);
    }

    /**
     * 判断成员是否持有 OWNER 角色。
     *
     * <p>角色判断一律经 {@code roleId → enterprise_role.code}（V3 的 {@code member_role}
     * 列已在 V7 迁移中退役）；角色不存在时按非 OWNER 处理。</p>
     */
    private boolean isOwnerRole(EnterpriseMember member) {
        return "OWNER".equals(roleCodeOf(member.getRoleId()));
    }

    /**
     * 查询角色编码；角色 ID 为空或角色记录不存在时返回 null。
     */
    private String roleCodeOf(Long roleId) {
        if (roleId == null) {
            return null;
        }
        EnterpriseRole role = enterpriseRoleMapper.selectById(roleId);
        return role == null ? null : role.getCode();
    }

    /**
     * 生成企业唯一标识 {@code slug}：使用去除横线后的完整 UUID 十六进制。
     *
     * <p>随机生成而非从名称推导：企业名称多为中文且允许后续修改，
     * 无法可靠转成稳定的英文 slug。完整 UUID 保留约 122 bit 随机性，
     * 同时由数据库唯一索引 {@code uk_enterprise_slug} 做最终一致性兜底。</p>
     */
    private String generateSlug() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
