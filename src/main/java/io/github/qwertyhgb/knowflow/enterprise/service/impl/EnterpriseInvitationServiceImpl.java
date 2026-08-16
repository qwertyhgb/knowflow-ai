package io.github.qwertyhgb.knowflow.enterprise.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.EnterpriseInvitationCreateRequest;
import io.github.qwertyhgb.knowflow.enterprise.dto.request.InvitationAcceptRequest;
import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseInvitation;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseInvitationStatus;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseMemberStatus;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseInvitationMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.service.EnterpriseInvitationService;
import io.github.qwertyhgb.knowflow.enterprise.vo.EnterpriseInvitationVO;
import io.github.qwertyhgb.knowflow.user.entity.User;
import io.github.qwertyhgb.knowflow.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 企业成员邀请业务服务实现。
 *
 * <p><strong>创建邀请流程：</strong></p>
 * <ol>
 *   <li>校验企业存在（404）与邀请人身份：必须是该企业的正常成员（403），
 *       且角色为 OWNER 或 ADMIN（MEMBER 无权邀请，403）。</li>
 *   <li>校验授予角色的分层规则：OWNER 角色不可通过邀请授予；
 *       ADMIN 只能邀请 MEMBER，仅 OWNER 可邀请 ADMIN（越权返回 403）。</li>
 *   <li>归一化被邀请邮箱（去首尾空白 + 转小写，与注册逻辑一致），
 *       拒绝邀请自己（400）。</li>
 *   <li>若该邮箱对应已注册用户且已有该企业成员关系（含 DISABLED），拒绝重复邀请（409）。</li>
 *   <li>检查重复待处理邀请：该邮箱在同一企业已存在<strong>未过期</strong>的
 *       PENDING 邀请则拒绝（409）；已过期的 PENDING 记录先惰性更新为
 *       EXPIRED，再继续创建。</li>
 *   <li>生成 32 位十六进制随机令牌，落库其 SHA-256 哈希（不存明文），
 *       插入一条新的 PENDING 邀请记录。</li>
 * </ol>
 *
 * <p><strong>为什么「重复待处理邀请」只靠业务层校验、不建唯一索引：</strong>
 * 该约束只针对「PENDING 且未过期」这一子集，MySQL 不支持部分唯一索引
 * （partial unique index），唯一索引无法表达；同时保留多条历史邀请记录
 * （已接受/已撤销/已过期）对排查与审计有价值。代价是并发创建同一邮箱的
 * 极端场景可能产生两条 PENDING 记录，学习项目接受这一权衡。</p>
 */
@Slf4j
@Service
public class EnterpriseInvitationServiceImpl implements EnterpriseInvitationService {

    private final EnterpriseMapper enterpriseMapper;

    private final EnterpriseMemberMapper enterpriseMemberMapper;

    private final EnterpriseInvitationMapper enterpriseInvitationMapper;

    private final UserMapper userMapper;

    /**
     * 可注入的 UTC 时钟，用于生成 {@code expiresAt} / {@code createdAt} / {@code updatedAt}：
     * 生产注入 {@code Clock.systemUTC()}，测试注入 {@code Clock.fixed(...)} 冻结时间。
     */
    private final Clock clock;

    /**
     * 邀请有效期，来自配置 {@code knowflow.enterprise.invitation-ttl}（默认 7 天），
     * 创建邀请时据此计算 {@code expires_at}。
     */
    private final Duration invitationTtl;

    public EnterpriseInvitationServiceImpl(EnterpriseMapper enterpriseMapper,
                                           EnterpriseMemberMapper enterpriseMemberMapper,
                                           EnterpriseInvitationMapper enterpriseInvitationMapper,
                                           UserMapper userMapper,
                                           Clock clock,
                                           @Value("${knowflow.enterprise.invitation-ttl:7d}") Duration invitationTtl) {
        if (invitationTtl == null || invitationTtl.isZero() || invitationTtl.isNegative()) {
            throw new IllegalArgumentException("Invitation TTL must be positive");
        }
        this.enterpriseMapper = enterpriseMapper;
        this.enterpriseMemberMapper = enterpriseMemberMapper;
        this.enterpriseInvitationMapper = enterpriseInvitationMapper;
        this.userMapper = userMapper;
        this.clock = clock;
        this.invitationTtl = invitationTtl;
    }

    @Override
    @Transactional
    public EnterpriseInvitationVO createInvitation(Long userId, Long enterpriseId,
                                                   EnterpriseInvitationCreateRequest request) {
        // 1. 企业必须存在 + 邀请人必须是该企业的管理成员（正常状态且角色为 OWNER/ADMIN）：
        //    先校验资源存在再做权限判断，避免把「不存在」误报成「无权限」；
        //    两条规则被列表、撤销接口复用，抽取为私有方法（requireEnterprise / requireManagerMember）。
        requireEnterprise(enterpriseId);
        EnterpriseMember inviterMember = requireManagerMember(userId, enterpriseId);

        // 2. 角色分层校验（顺序：先校验邀请人资格，再校验目标角色）：
        EnterpriseMemberRole grantedRole = request.getRole();
        //    2.1 OWNER 是企业创建者的专属角色，不通过邀请授予。
        if (grantedRole == EnterpriseMemberRole.OWNER) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        //    2.2 ADMIN 只能邀请 MEMBER；邀请 ADMIN 仅 OWNER 可为（分层规则）。
        if (inviterMember.getMemberRole() == EnterpriseMemberRole.ADMIN
                && grantedRole == EnterpriseMemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // 3. 归一化被邀请邮箱：与注册逻辑（UserServiceImpl#normalizeEmail）保持完全一致，
        //    否则「注册时的小写邮箱」与「邀请时的大小写混用邮箱」将无法匹配。
        String inviteeEmail = normalizeEmail(request.getEmail());

        // 邀请人邮箱用于「不能邀请自己」校验；成员关系的外键已保证该用户存在。
        User inviterUser = userMapper.selectById(userId);
        if (inviteeEmail.equals(inviterUser.getEmail())) {
            throw new BusinessException(ErrorCode.SELF_INVITATION_NOT_ALLOWED);
        }

        // 4. 被邀请邮箱若对应已注册用户，且已经存在该企业的成员关系，则拒绝重复邀请。
        //    DISABLED 成员应由管理员通过成员管理恢复，不能借邀请绕过原成员关系；
        //    邮箱尚未注册时不拦截：对方先接受前注册即可。
        User inviteeUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, inviteeEmail));
        if (inviteeUser != null && enterpriseMemberMapper.exists(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, inviteeUser.getId()))) {
            throw new BusinessException(ErrorCode.INVITEE_ALREADY_MEMBER);
        }

        // 5. 重复待处理校验 + 惰性过期：
        //    同一企业对同一邮箱最多一条「PENDING 且未过期」的邀请。
        Instant now = clock.instant();
        EnterpriseInvitation pendingInvitation = enterpriseInvitationMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseInvitation::getInviteeEmail, inviteeEmail)
                        .eq(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.PENDING));
        if (pendingInvitation != null) {
            if (pendingInvitation.getExpiresAt().isAfter(now)) {
                // 仍在有效期内：直接拒绝，避免令牌满天飞。
                throw new BusinessException(ErrorCode.INVITATION_ALREADY_PENDING);
            }
            // 已过期但状态仍是 PENDING：先落为 EXPIRED 再继续。
            // 过期本应由后台任务统一扫描（V4 已备好 (status, expires_at) 索引），
            // 学习版暂不引入定时任务，这里在创建新邀请时「顺带」完成状态收敛。
            expirePendingInvitation(pendingInvitation.getId(), now);
        }

        // 6. 生成邀请令牌并插入新邀请记录。
        //    令牌与登录 Token 同款生成方式（去横线 UUID，约 122 bit 随机），
        //    落库只存 SHA-256 哈希：数据库泄露也不泄露可直接使用的令牌，
        //    与密码不存明文是同一条安全底线。
        String token = generateToken();
        EnterpriseInvitation invitation = new EnterpriseInvitation();
        invitation.setEnterpriseId(enterpriseId);
        invitation.setInviterUserId(userId);
        invitation.setInviteeEmail(inviteeEmail);
        invitation.setMemberRole(grantedRole);
        invitation.setTokenHash(sha256Hex(token));
        invitation.setStatus(EnterpriseInvitationStatus.PENDING);
        invitation.setExpiresAt(now.plus(invitationTtl));
        invitation.setCreatedAt(now);
        invitation.setUpdatedAt(now);
        enterpriseInvitationMapper.insert(invitation);

        // 日志只记录系统生成的标识：邮箱属于个人信息，不落日志。
        log.info("event=invitation_created invitationId={} enterpriseId={} inviterId={}",
                invitation.getId(), enterpriseId, userId);
        // 明文令牌只随本次响应返回一次，之后系统只存哈希、无法找回。
        return EnterpriseInvitationVO.from(invitation, token, null);
    }

    @Override
    @Transactional
    public EnterpriseInvitationVO acceptInvitation(Long userId, InvitationAcceptRequest request) {
        // 1. 令牌归一化（容忍首尾空白）后哈希，按哈希精确查库定位邀请。
        //    令牌即凭证：数据库不存明文，只能以 token_hash 为键定位，
        //    与唯一索引 uk_enterprise_invitation_token 配合保证令牌唯一。
        String token = request.getToken().strip();
        Instant now = clock.instant();
        EnterpriseInvitation invitation = enterpriseInvitationMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getTokenHash, sha256Hex(token)));
        if (invitation == null) {
            throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND);
        }

        // 2. 状态机校验：只有「PENDING 且未过期」的邀请可以接受。
        //    这里不在抛错前写 EXPIRED：BusinessException 会触发当前事务回滚，
        //    那样看似更新成功、实际却不会落库。过期状态由邀请列表或再次邀请时收敛。
        requirePendingInvitation(invitation, now);

        // 4. 身份校验：邀请是发给 inviteeEmail 的，只有登录邮箱与之匹配的账号才能接受。
        //    这是「邮箱邀请」模型的核心校验：令牌证明持有者收到了邀请，
        //    邮箱匹配证明持有者就是被邀请人（允许未注册邮箱，先注册再接受）。
        User user = userMapper.selectById(userId);
        if (!invitation.getInviteeEmail().equals(user.getEmail())) {
            throw new BusinessException(ErrorCode.INVITATION_EMAIL_MISMATCH);
        }

        // 4. 防御性校验：只要已经存在成员关系（含 DISABLED），就不重复插入。
        //    被禁用成员应由管理员显式恢复，邀请流程不能绕过成员管理规则。
        if (enterpriseMemberMapper.exists(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, invitation.getEnterpriseId())
                        .eq(EnterpriseMember::getUserId, userId))) {
            throw new BusinessException(ErrorCode.INVITEE_ALREADY_MEMBER);
        }

        // 5. 建立成员关系：角色取邀请时授予的角色，字段组装与企业创建流程同构。
        EnterpriseMember member = new EnterpriseMember();
        member.setEnterpriseId(invitation.getEnterpriseId());
        member.setUserId(userId);
        member.setMemberRole(invitation.getMemberRole());
        member.setStatus(EnterpriseMemberStatus.NORMAL);
        member.setJoinedAt(now);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        try {
            enterpriseMemberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            // 唯一索引 (enterprise_id, user_id) 兜底并发重复接受：
            // 两个请求同时通过第 5 步检查时，后插入者在此撞唯一键，转为业务错误而非 500。
            throw new BusinessException(ErrorCode.INVITEE_ALREADY_MEMBER);
        }

        // 6. 以条件更新原子地把邀请从 PENDING 流转为 ACCEPTED。
        //    状态与过期时间都放进 WHERE，确保并发撤销/接受只有一个操作能成功；
        //    更新失败会抛异常并回滚上面的成员插入，不会留下半完成状态。
        EnterpriseInvitation accept = new EnterpriseInvitation();
        accept.setStatus(EnterpriseInvitationStatus.ACCEPTED);
        accept.setAcceptedByUserId(userId);
        accept.setAcceptedAt(now);
        accept.setUpdatedAt(now);
        int transitioned = enterpriseInvitationMapper.update(accept,
                new LambdaUpdateWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getId, invitation.getId())
                        .eq(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.PENDING)
                        .gt(EnterpriseInvitation::getExpiresAt, now));
        if (transitioned != 1) {
            throwCurrentInvitationState(invitation.getId(), now);
        }

        // 内存中的实体同步为已接受状态，用于组装响应（避免重新查库）。
        invitation.setStatus(EnterpriseInvitationStatus.ACCEPTED);
        invitation.setAcceptedByUserId(userId);
        invitation.setAcceptedAt(now);
        invitation.setUpdatedAt(now);

        // 日志只记录系统生成的标识：邮箱属于个人信息，不落日志。
        log.info("event=invitation_accepted invitationId={} enterpriseId={} userId={}",
                invitation.getId(), invitation.getEnterpriseId(), userId);
        // 接受响应不携带令牌：令牌只在创建邀请时返回一次，之后无法找回。
        return EnterpriseInvitationVO.from(invitation, null, null);
    }

    @Override
    @Transactional
    public List<EnterpriseInvitationVO> listEnterpriseInvitations(Long userId, Long enterpriseId) {
        // 权限：企业存在（404）+ 当前用户是管理成员（403），与创建邀请一致。
        requireEnterprise(enterpriseId);
        requireManagerMember(userId, enterpriseId);

        // 列表返回前批量收敛该企业已经到期的 PENDING 邀请，避免管理端长期看到过时状态。
        Instant now = clock.instant();
        enterpriseInvitationMapper.update(null,
                new LambdaUpdateWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.PENDING)
                        .le(EnterpriseInvitation::getExpiresAt, now)
                        .set(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.EXPIRED)
                        .set(EnterpriseInvitation::getUpdatedAt, now));

        // 全部邀请按创建时间倒序返回（最新邀请在前），暂不分页，与成员列表保持一致。
        List<EnterpriseInvitation> invitations = enterpriseInvitationMapper.selectList(
                new LambdaQueryWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getEnterpriseId, enterpriseId)
                        .orderByDesc(EnterpriseInvitation::getCreatedAt)
                        .orderByDesc(EnterpriseInvitation::getId));
        return invitations.stream()
                .map(invitation -> EnterpriseInvitationVO.from(invitation, null, null))
                .toList();
    }

    @Override
    @Transactional
    public List<EnterpriseInvitationVO> listMyPendingInvitations(Long userId) {
        // 1. 被邀请人视角按登录邮箱匹配邀请（与注册邮箱的归一化规则一致）。
        String email = userMapper.selectById(userId).getEmail();
        Instant now = clock.instant();

        // 2. 批量惰性过期：把该邮箱「已过期但仍标记 PENDING」的历史记录一次性置为 EXPIRED。
        //    批量按条件更新必须用
        //    LambdaUpdateWrapper 显式 SET（updateById 只按主键更新单条，且会跳过 null 字段）。
        enterpriseInvitationMapper.update(null,
                new LambdaUpdateWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getInviteeEmail, email)
                        .eq(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.PENDING)
                        .le(EnterpriseInvitation::getExpiresAt, now)
                        .set(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.EXPIRED)
                        .set(EnterpriseInvitation::getUpdatedAt, now));

        // 3. 只查「PENDING 且未过期」的邀请，按过期时间升序（最先过期的排最前，提示优先处理）。
        List<EnterpriseInvitation> invitations = enterpriseInvitationMapper.selectList(
                new LambdaQueryWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getInviteeEmail, email)
                        .eq(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.PENDING)
                        .gt(EnterpriseInvitation::getExpiresAt, now)
                        .orderByAsc(EnterpriseInvitation::getExpiresAt)
                        .orderByAsc(EnterpriseInvitation::getId));
        if (invitations.isEmpty()) {
            // 没有待处理邀请：直接返回空列表，避免后续空 IN 查询。
            return List.of();
        }

        // 4. 批量查询企业名称（被邀请人需要知道是哪家企业发来的邀请），避免 N+1。
        List<Long> enterpriseIds = invitations.stream()
                .map(EnterpriseInvitation::getEnterpriseId)
                .distinct()
                .toList();
        Map<Long, Enterprise> enterpriseMap = enterpriseMapper.selectByIds(enterpriseIds).stream()
                .collect(Collectors.toMap(Enterprise::getId, Function.identity()));
        return invitations.stream()
                .map(invitation -> {
                    // 企业记录不存在（异常情况）时名称留空，不阻断列表返回。
                    Enterprise enterprise = enterpriseMap.get(invitation.getEnterpriseId());
                    return EnterpriseInvitationVO.from(invitation, null,
                            enterprise != null ? enterprise.getName() : null);
                })
                .toList();
    }

    @Override
    @Transactional
    public EnterpriseInvitationVO revokeInvitation(Long userId, Long enterpriseId, Long invitationId) {
        // 1. 权限：企业存在（404）+ 当前用户是管理成员（403），与创建/列表一致。
        requireEnterprise(enterpriseId);
        requireManagerMember(userId, enterpriseId);

        // 2. 按「企业 + 邀请 ID」双重条件定位：邀请不存在或不属于该企业统一返回 404，
        //    不泄露其他企业的邀请存在性。
        EnterpriseInvitation invitation = enterpriseInvitationMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseInvitation::getId, invitationId));
        if (invitation == null) {
            throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND);
        }

        // 3. 状态机校验：只有「PENDING 且未过期」的邀请可以撤销。
        Instant now = clock.instant();
        requirePendingInvitation(invitation, now);

        // 4. 条件更新保证只有仍处于 PENDING 且未过期的邀请能撤销；
        //    与接受邀请竞争时，两个操作最多成功一个。
        EnterpriseInvitation revoke = new EnterpriseInvitation();
        revoke.setStatus(EnterpriseInvitationStatus.REVOKED);
        revoke.setUpdatedAt(now);
        int transitioned = enterpriseInvitationMapper.update(revoke,
                new LambdaUpdateWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getId, invitation.getId())
                        .eq(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.PENDING)
                        .gt(EnterpriseInvitation::getExpiresAt, now));
        if (transitioned != 1) {
            throwCurrentInvitationState(invitation.getId(), now);
        }

        // 内存中的实体同步为已撤销状态，用于组装响应（避免重新查库）。
        invitation.setStatus(EnterpriseInvitationStatus.REVOKED);
        invitation.setUpdatedAt(now);

        // 日志只记录系统生成的标识：邮箱属于个人信息，不落日志。
        log.info("event=invitation_revoked invitationId={} enterpriseId={} userId={}",
                invitation.getId(), enterpriseId, userId);
        return EnterpriseInvitationVO.from(invitation, null, null);
    }

    /**
     * 生成明文邀请令牌：去除横线的 UUID 十六进制（32 个字符）。
     *
     * <p>随机令牌的作用是「证明持有者有权接受这条邀请」：接受接口凭令牌定位
     * 邀请记录并校验哈希，避免仅凭 enterpriseId 就能加入企业。</p>
     */
    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 计算字符串的 SHA-256 哈希，返回 64 位小写十六进制字符
     * （与 {@code token_hash CHAR(64)} 列长度精确匹配）。
     *
     * @param raw 原始字符串（明文令牌）
     * @return 十六进制哈希值
     */
    private String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // HexFormat 是 Java 17+ 的标准十六进制工具，替代手写循环拼接。
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 规范强制要求的算法，正常环境不会走到这里；
            // 真缺失说明运行环境损坏，只能快速失败，不能降级存明文。
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 归一化邮箱：去首尾空白 + 按 ROOT 区域转小写。
     *
     * <p>与 {@code UserServiceImpl#normalizeEmail} 逻辑一致。学习版不抽公共工具类，
     * 两处各自实现并保持同步；若后续出现第三处使用场景，再评估抽取。</p>
     */
    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 把仍为 PENDING 且已经到期的邀请原子地收敛为 EXPIRED。
     * 条件更新避免覆盖刚被其他请求接受或撤销的终态。
     */
    private void expirePendingInvitation(Long invitationId, Instant now) {
        enterpriseInvitationMapper.update(null,
                new LambdaUpdateWrapper<EnterpriseInvitation>()
                        .eq(EnterpriseInvitation::getId, invitationId)
                        .eq(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.PENDING)
                        .le(EnterpriseInvitation::getExpiresAt, now)
                        .set(EnterpriseInvitation::getStatus, EnterpriseInvitationStatus.EXPIRED)
                        .set(EnterpriseInvitation::getUpdatedAt, now));
    }

    /** 校验邀请当前可操作，并把各终态映射为稳定的业务错误码。 */
    private void requirePendingInvitation(EnterpriseInvitation invitation, Instant now) {
        if (invitation.getStatus() == EnterpriseInvitationStatus.ACCEPTED) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_ACCEPTED);
        }
        if (invitation.getStatus() == EnterpriseInvitationStatus.REVOKED) {
            throw new BusinessException(ErrorCode.INVITATION_REVOKED);
        }
        if (invitation.getStatus() == EnterpriseInvitationStatus.EXPIRED
                || !invitation.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
        if (invitation.getStatus() != EnterpriseInvitationStatus.PENDING) {
            throw new IllegalStateException("Unsupported invitation status: " + invitation.getStatus());
        }
    }

    /**
     * 条件状态流转失败后重新读取数据库中的最终状态，向并发失败方返回准确业务错误。
     */
    private void throwCurrentInvitationState(Long invitationId, Instant now) {
        EnterpriseInvitation current = enterpriseInvitationMapper.selectById(invitationId);
        if (current == null) {
            throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND);
        }
        requirePendingInvitation(current, now);
        throw new IllegalStateException("Invitation state transition affected no row: " + invitationId);
    }

    /**
     * 查询企业，不存在时抛 404。
     *
     * <p>统一「资源不存在」的判定，供创建、列表、撤销复用，
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
     * 校验当前用户是该企业的<strong>管理成员</strong>，否则抛 403。
     *
     * <p>管理成员 = 成员关系存在、状态正常、角色为 OWNER 或 ADMIN。
     * 创建邀请、邀请列表、撤销邀请共用此规则（邀请信息比成员列表更敏感，
     * 因此与「查看成员列表」的权限不同——后者仅要求正常成员）。</p>
     *
     * <p>返回成员关系实体，供调用方继续做角色相关的细化判断
     * （如创建邀请时的 ADMIN 分层规则）。</p>
     */
    private EnterpriseMember requireManagerMember(Long userId, Long enterpriseId) {
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
        return member;
    }
}
