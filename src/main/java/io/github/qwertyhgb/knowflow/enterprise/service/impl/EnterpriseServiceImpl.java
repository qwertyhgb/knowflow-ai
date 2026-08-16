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
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
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

    /**
     * 可注入的 UTC 时钟，用于生成 {@code createdAt} / {@code updatedAt} / {@code joinedAt}：
     * 生产注入 {@code Clock.systemUTC()}，测试注入 {@code Clock.fixed(...)} 冻结时间。
     */
    private final Clock clock;

    /** 用户表 Mapper，用于成员列表关联查询邮箱/昵称。 */
    private final UserMapper userMapper;

    public EnterpriseServiceImpl(EnterpriseMapper enterpriseMapper,
                                 EnterpriseMemberMapper enterpriseMemberMapper,
                                 UserMapper userMapper,
                                 Clock clock) {
        this.enterpriseMapper = enterpriseMapper;
        this.enterpriseMemberMapper = enterpriseMemberMapper;
        this.userMapper = userMapper;
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

        // 创建者自动成为企业所有者，与企业同属一个事务。
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(enterprise.getId());
        member.setUserId(userId);
        member.setMemberRole(EnterpriseMemberRole.OWNER);
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        member.setJoinedAt(now);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        enterpriseMemberMapper.insert(member);

        // 日志只记录系统生成的标识（enterpriseId、userId），不记录企业名称等用户自由文本。
        log.info("event=enterprise_created enterpriseId={} userId={}", enterprise.getId(), userId);
        return EnterpriseVO.from(enterprise);
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
        // 企业不存在 → 404；非正常成员 → 403。校验逻辑抽取到私有方法复用。
        Enterprise enterprise = requireEnterprise(enterpriseId);
        requireActiveMember(userId, enterpriseId);
        return EnterpriseVO.from(enterprise);
    }

    @Override
    @Transactional
    public EnterpriseVO updateEnterprise(Long userId, Long enterpriseId, EnterpriseUpdateRequest request) {
        // 1. 企业不存在直接返回 404；先校验资源存在再做权限判断，
        //    避免把「不存在」误报成「无权限」。
        Enterprise enterprise = requireEnterprise(enterpriseId);

        // 2. 校验成员关系：必须存在且状态正常，否则 403。
        EnterpriseMember member = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, userId));
        if (member == null || member.getStatus() != EnterpriseMemberStatus.NORMAL) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // 3. 角色校验：仅管理角色（OWNER 或 ADMIN）可修改企业设置。
        //    OWNER 是创建者、拥有全部权限，必须一并放行——否则创建者反而无法修改自己的企业。
        if (member.getMemberRole() != EnterpriseMemberRole.OWNER
                && member.getMemberRole() != EnterpriseMemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // 4. 只更新允许变更的字段，避免把查询到的旧 slug/status/createdAt 回写，
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
        // ADMIN 与 MEMBER 均可查看成员列表，故此处不做角色限制。
        requireEnterprise(enterpriseId);
        requireActiveMember(userId, enterpriseId);

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

        // 3. 组装 VO；若某 userId 在 sys_user 中已不存在，email/nickname 留空。
        return members.stream()
                .map(member -> EnterpriseMemberVO.from(member, userMap.get(member.getUserId())))
                .toList();
    }

    @Override
    @Transactional
    public void removeMember(Long userId, Long enterpriseId, Long targetUserId) {
        // 1. 企业必须存在：先校验资源存在再做权限判断，避免把「不存在」误报成「无权限」。
        requireEnterprise(enterpriseId);

        // 2. 当前用户必须是该企业正常成员且角色为 OWNER/ADMIN（禁止 MEMBER）。
        requireAdminRole(userId, enterpriseId);

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
        if (targetMember.getMemberRole() == EnterpriseMemberRole.OWNER) {
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
        requireEnterprise(enterpriseId);

        // 2. 退出不需要管理员角色，只按「企业 + 当前用户 ID」查成员关系。
        EnterpriseMember member = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, userId));
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // 3. OWNER 不能主动退出，否则企业会失去所有者；这与管理员移除 OWNER 的语义不同。
        if (member.getMemberRole() == EnterpriseMemberRole.OWNER) {
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
        // 1. 企业必须存在 + 当前用户是管理成员：与移除成员共用同一套前置校验。
        requireEnterprise(enterpriseId);
        requireAdminRole(userId, enterpriseId);

        // 2. 目标成员必须在企业中（404，不泄露存在性）。
        EnterpriseMember targetMember = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, targetUserId));
        if (targetMember == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // 3. 目标为 OWNER 时拒绝：所有者状态不可修改（禁用 OWNER 等价于移除所有者）。
        if (targetMember.getMemberRole() == EnterpriseMemberRole.OWNER) {
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
     * 组装成员视图：关联目标用户的公开信息（邮箱/昵称）；
     * 用户记录不存在（异常情况）时邮箱/昵称留空，与成员列表行为一致。
     */
    private EnterpriseMemberVO toMemberVO(EnterpriseMember member) {
        User user = userMapper.selectById(member.getUserId());
        return EnterpriseMemberVO.from(member, user);
    }

    /**
     * 查询企业，不存在时抛 404。
     *
     * <p>统一「资源不存在」的判定，供详情、更新、成员列表复用，
     * 保证错误码与校验顺序（先资源后权限）在各接口一致。</p>
     */
    private Enterprise requireEnterprise(Long enterpriseId) {
        Enterprise enterprise = enterpriseMapper.selectById(enterpriseId);
        if (enterprise == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return enterprise;
    }

    /**
     * 校验当前用户是否为该企业的正常成员，否则抛 403。
     *
     * <p>核心安全规则：<strong>知道 {@code enterpriseId} 不等于有权访问企业</strong>，
     * 只有「成员关系存在且状态为正常」才放行。抽取为私有方法供详情、成员列表复用，
     * 保证安全判定逻辑单一来源，不会因多处重复实现而出现不一致。</p>
     */
    private void requireActiveMember(Long userId, Long enterpriseId) {
        boolean isActiveMember = enterpriseMemberMapper.exists(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, userId)
                        .eq(EnterpriseMember::getStatus, EnterpriseMemberStatus.NORMAL));
        if (!isActiveMember) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * 校验当前用户是该企业的<strong>管理成员</strong>（OWNER / ADMIN），否则抛 403。
     *
     * <p>与 {@link #requireActiveMember} 相比，本方法额外检查角色——
     * 只允许 OWNER 和 ADMIN（含被禁用的成员同样不能执行管理操作）。
     * 移除成员、创建邀请等写操作均复用此校验。</p>
     */
    private void requireAdminRole(Long userId, Long enterpriseId) {
        EnterpriseMember member = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, userId));
        if (member == null || member.getStatus() != EnterpriseMemberStatus.NORMAL) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (member.getMemberRole() != EnterpriseMemberRole.OWNER
                && member.getMemberRole() != EnterpriseMemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
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
