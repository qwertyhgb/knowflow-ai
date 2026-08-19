package io.github.qwertyhgb.knowflow.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseMember;
import io.github.qwertyhgb.knowflow.enterprise.entity.EnterpriseRole;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseRoleStatus;
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
import io.github.qwertyhgb.knowflow.knowledge.service.KnowledgeBaseCacheKeys;
import io.github.qwertyhgb.knowflow.knowledge.service.KnowledgeBaseService;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseMemberVO;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库业务服务实现。
 *
 * <p><strong>权限分层模型（写入本实现的核心设计）</strong>：</p>
 * <ul>
 *   <li><strong>企业级权限码</strong>（{@code @PreAuthorize} + authorities）表达「能否做某类
 *       操作」，例如能否创建部门、移除成员；权限码<strong>无法携带资源 ID</strong>。</li>
 *   <li><strong>资源级成员角色</strong>（{@code knowledge_base_member.member_role}）表达
 *       「能否在某个知识库上操作」。两层权限互为补充，缺一不可。</li>
 * </ul>
 *
 * <p>因此知识库接口<strong>不加 {@code @PreAuthorize}</strong>：创建知识库是「企业正常成员
 * 的基础能力」，只要求成员身份（本方法用 {@link EnterpriseMembershipChecker} 校验）；
 * 知识库内的更新/删除/成员管理等操作将由后续实现按资源成员角色校验。</p>
 *
 * <p><strong>可见性模型（列表/详情）：</strong>知识库对当前用户可见的条件 =
 * {@code access_mode=PUBLIC}（企业内所有正常成员可见）<em>或</em> 当前用户是其成员
 * （{@code knowledge_base_member} 存在记录），两层取<strong>并集</strong>。
 * 实现上列表用「两次查询内存合并」而非 JOIN：一次查企业下全部 NORMAL 知识库，
 * 一次查当前用户的成员记录，内存里按 {@code knowledgeBaseId} 建 Map 标注 {@code myRole}，
 * 既避免 N+1，也避免为教学阶段引入复杂 SQL。</p>
 *
 * <p><strong>隐私保护设计：</strong>PRIVATE 知识库对非成员查详情返回 404 而非 403，
 * 不泄露「这个知识库存在」的事实（详见 {@code getKnowledgeBase}）。</p>
 *
 * <p><strong>管理权限模型（两级管理员取或）：</strong>知识库管理操作（添加/改角色/移除成员、
 * 更新、删除、状态）要求满足如下两级管理员之一：</p>
 * <ul>
 *   <li><strong>资源级</strong>：当前用户是该知识库成员且 {@code memberRole=ADMIN}；</li>
 *   <li><strong>企业级</strong>：当前用户的企业角色是 OWNER 或 ADMIN（企业管理者可管理
 *       企业内所有知识库）。</li>
 * </ul>
 * <p>统一由私有方法 {@code requireKnowledgeBaseManager} 校验。这些操作同样<strong>不加
 * {@code @PreAuthorize}</strong>——权限码无法携带资源 ID，「能否管理某个知识库」必须落在
 * 资源级的成员角色上，由 Service 判定。</p>
 */
@Slf4j
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final KnowledgeBaseMemberMapper knowledgeBaseMemberMapper;

    /** 企业成员身份校验（企业存在 → 404；正常成员 → 403），与成员/部门等模块共用。 */
    private final EnterpriseMembershipChecker membershipChecker;

    /** 企业成员表 Mapper：两级管理员校验时读取操作者企业角色（roleId）用。 */
    private final EnterpriseMemberMapper enterpriseMemberMapper;

    /** 企业角色表 Mapper：两级管理员校验时把 roleId 解析为角色编码用。 */
    private final EnterpriseRoleMapper enterpriseRoleMapper;

    /** 可注入的 UTC 时钟，便于测试冻结创建时间。 */
    private final Clock clock;

    /** Redis 字符串模板：Cache Aside 缓存的读写入口（与 TokenService 同款）。 */
    private final StringRedisTemplate redisTemplate;

    /** Jackson 3 统一 JSON 工具：缓存值序列化/反序列化（与全项目同一 Bean）。 */
    private final JsonMapper jsonMapper;

    /**
     * 详情缓存空值标记：知识库查询不到（或当前用户无权限）时缓存这个特殊字符串
     * 而非真实 JSON。它是防缓存穿透的手段——「不存在」也被缓存，下次同样查询
     * 不再反复打数据库。真实 VO 序列化结果必以 {@code {}} 开头，不会与它混淆。
     */
    private static final String CACHE_NULL_MARKER = "NULL";

    /** 类型引用：把缓存 JSON 反序列化为 {@code List<KnowledgeBaseVO>} 时使用。 */
    private static final TypeReference<List<KnowledgeBaseVO>> LIST_VO_TYPE =
            new TypeReference<List<KnowledgeBaseVO>>() {
            };

    /** 企业级可管理知识库的角色编码集合（企业所有者与管理员可管理企业内全部知识库）。 */
    private static final Set<String> ENTERPRISE_MANAGER_ROLES = Set.of("OWNER", "ADMIN");

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper,
                                    KnowledgeBaseMemberMapper knowledgeBaseMemberMapper,
                                    EnterpriseMembershipChecker membershipChecker,
                                    EnterpriseMemberMapper enterpriseMemberMapper,
                                    EnterpriseRoleMapper enterpriseRoleMapper,
                                    Clock clock,
                                    StringRedisTemplate redisTemplate,
                                    JsonMapper jsonMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseMemberMapper = knowledgeBaseMemberMapper;
        this.membershipChecker = membershipChecker;
        this.enterpriseMemberMapper = enterpriseMemberMapper;
        this.enterpriseRoleMapper = enterpriseRoleMapper;
        this.clock = clock;
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional
    public KnowledgeBase createKnowledgeBase(Long userId, Long enterpriseId,
                                             KnowledgeBaseCreateRequest request) {
        // 1. 先校验企业存在，再校验成员身份，保持与现有企业业务一致：先资源后权限，
        //    避免把「企业不存在」误报成「无权限」。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 组装知识库：名称去除首尾空白，访问模式未传时默认 PRIVATE，
        //    状态与时间由后端统一赋值，避免客户端绕过创建规则。
        Instant now = clock.instant();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setEnterpriseId(enterpriseId);
        knowledgeBase.setName(request.getName().strip());
        knowledgeBase.setDescription(request.getDescription());
        knowledgeBase.setAccessMode(request.getAccessMode() == null
                ? KnowledgeBaseAccessMode.PRIVATE
                : request.getAccessMode());
        knowledgeBase.setOwnerUserId(userId);
        knowledgeBase.setStatus(KnowledgeBaseStatus.NORMAL);
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);
        knowledgeBaseMapper.insert(knowledgeBase);

        // 3. 同一事务内写入创建者成员记录：创建者自动成为知识库管理员（ADMIN）。
        //    这是资源级权限的初始事实——若无此记录，创建者将对自己的知识库毫无权限。
        KnowledgeBaseMember ownerMember = new KnowledgeBaseMember();
        ownerMember.setKnowledgeBaseId(knowledgeBase.getId());
        ownerMember.setEnterpriseId(enterpriseId);
        ownerMember.setUserId(userId);
        ownerMember.setMemberRole(KnowledgeBaseMemberRole.ADMIN);
        ownerMember.setCreatedAt(now);
        ownerMember.setUpdatedAt(now);
        knowledgeBaseMemberMapper.insert(ownerMember);

        // 只记录系统标识，不记录用户提交的知识库名称或描述。
        log.info("event=knowledge_base_created enterpriseId={} knowledgeBaseId={} ownerId={}",
                enterpriseId, knowledgeBase.getId(), userId);

        // ==================== Cache Aside 写路径：失效列表缓存 ====================
        // 新知识库会进入列表：创建者（PRIVATE 库）或企业全部成员（PUBLIC 库）。
        // 列表键按用户分键，直接按企业通配 pattern 整组失效，统一且无害。
        // 详情键是新 ID、此前不可能有缓存（没人查过不存在的 ID——即便查过也只会
        // 留下空值标记，短 TTL 很快自然过期），所以只需删列表键，无需删详情键。
        evictListCachesForEnterprise(enterpriseId);
        return knowledgeBase;
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBaseVO> listVisibleKnowledgeBases(Long userId, Long enterpriseId) {
        // 1. 与其他企业作用域查询一致：先判断企业是否存在，再校验正常成员身份（先资源后权限）。
        //    注意：缓存不能替代权限校验——用户退出企业后必须立即失去可见性，
        //    所以权限校验始终执行，缓存只替换「知识库数据的查询」。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // ==================== Cache Aside 读路径：先查缓存 ====================
        // 命中 → 反序列化返回，不查数据库（省掉下面两次 DB 查询）。
        String cacheKey = KnowledgeBaseCacheKeys.listKey(enterpriseId, userId);
        String cached = getCache(cacheKey);
        if (cached != null) {
            try {
                return jsonMapper.readValue(cached, LIST_VO_TYPE);
            } catch (RuntimeException ex) {
                // 缓存内容损坏（如升级后 JSON 结构变化）：降级走数据库并重建缓存，
                // 而不是把损坏数据抛给用户或一直报错。日志只记键名，不记内容。
                log.warn("event=kb_cache_deserialize_failed cacheKey={} errorType={}",
                        cacheKey, ex.getClass().getSimpleName());
            }
        }

        // ==================== 未命中：查数据库 ====================
        // 2. 一次查出当前企业下全部 NORMAL 知识库，按 createdAt、id 倒序（新建在前、稳定排序）。
        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getEnterpriseId, enterpriseId)
                        .eq(KnowledgeBase::getStatus, KnowledgeBaseStatus.NORMAL)
                        .orderByDesc(KnowledgeBase::getCreatedAt)
                        .orderByDesc(KnowledgeBase::getId));
        if (knowledgeBases.isEmpty()) {
            // 空结果也缓存（"[]"），TTL 用短的空值 TTL——防缓存穿透：列表为空也是
            // 一种高频查询结果，不缓存会让每次请求都白查数据库。
            putCache(cacheKey, "[]", Duration.ofSeconds(KnowledgeBaseCacheKeys.EMPTY_TTL_SECONDS));
            return List.of();
        }

        // 3. 可见性过滤后列表 = PUBLIC（企业所有正常成员可见）∪ 我的成员记录。
        //    这里用「两次查询 + 内存合并」，而非把可见性直接压进 SQL JOIN：
        //    一是避免 N+1（一次查出全部成员记录，内存建 Map 标注 myRole）；
        //    二是不为教学阶段引入复杂 SQL。查出当前用户的全部成员记录，按 knowledgeBaseId
        //    建 Map，随后在流合并处过滤出「PUBLIC 或我是成员」的知识库。
        List<KnowledgeBaseMember> myMemberships = knowledgeBaseMemberMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getUserId, userId));
        Map<Long, KnowledgeBaseMemberRole> roleByKnowledgeBaseId = new HashMap<>();
        for (KnowledgeBaseMember member : myMemberships) {
            roleByKnowledgeBaseId.put(member.getKnowledgeBaseId(), member.getMemberRole());
        }

        // 4. 过滤并生成 VO：只保留 PUBLIC 知识库或我作为成员的知识库，跳过其余
        //    （PRIVATE 且非成员 → 不泄露存在性，与 getKnowledgeBase 的隐私设计一致）。
        //    filter 保留满足条件的项，原流顺序（createdAt 倒序、id 倒序）保持不变，
        //    满足条件即生成 VO：成员 → 带 myRole；PUBLIC 非成员 → myRole 为 null。
        List<KnowledgeBaseVO> result = knowledgeBases.stream()
                .filter(kb -> kb.getAccessMode() == KnowledgeBaseAccessMode.PUBLIC
                        || roleByKnowledgeBaseId.containsKey(kb.getId()))
                .map(kb -> KnowledgeBaseVO.from(kb, roleByKnowledgeBaseId.get(kb.getId())))
                .toList();

        // ==================== 回填缓存 ====================
        // 非空结果用「基础 TTL + 随机抖动」——同批写入的键过期时间错开，防雪崩。
        putCache(cacheKey, jsonMapper.writeValueAsString(result),
                KnowledgeBaseCacheKeys.randomTtl(KnowledgeBaseCacheKeys.LIST_TTL_SECONDS));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeBaseVO getKnowledgeBase(Long userId, Long enterpriseId, Long knowledgeBaseId) {
        // 1. 先判断企业是否存在，再校验正常成员身份（先资源后权限）。
        //    与列表一致：权限校验永远执行，缓存只替换数据查询。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // ==================== Cache Aside 读路径：先查缓存 ====================
        String cacheKey = KnowledgeBaseCacheKeys.detailKey(enterpriseId, knowledgeBaseId, userId);
        String cached = getCache(cacheKey);
        if (cached != null) {
            // 缓存的是空值标记（上一次查询「不存在/无权限」）→ 直接 404，
            // 不再打数据库——这就是缓存穿透防护：「不存在」也有缓存。
            if (CACHE_NULL_MARKER.equals(cached)) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            try {
                return jsonMapper.readValue(cached, KnowledgeBaseVO.class);
            } catch (RuntimeException ex) {
                // 缓存内容损坏：降级走数据库并重建缓存（理由同列表）。
                log.warn("event=kb_cache_deserialize_failed cacheKey={} errorType={}",
                        cacheKey, ex.getClass().getSimpleName());
            }
        }

        // ==================== 未命中：查数据库 ====================
        // 2. 用「id + enterpriseId」组合定位知识库，防止跨企业读取（多租户越权防护）。
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getEnterpriseId, enterpriseId));
        // 不存在或已禁用（DISABLED）都按「不存在」处理：不泄露知识库是否真实存在及其状态。
        // 404 前回填空值缓存（短 TTL）——防穿透，让「不存在」的查询也有缓存可命中。
        if (knowledgeBase == null || knowledgeBase.getStatus() != KnowledgeBaseStatus.NORMAL) {
            putCache(cacheKey, CACHE_NULL_MARKER,
                    Duration.ofSeconds(KnowledgeBaseCacheKeys.EMPTY_TTL_SECONDS));
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // 3. 查当前用户对该知识库的成员记录（knowledgeBaseId + userId 精确定位）。
        KnowledgeBaseMember myMembership = knowledgeBaseMemberMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeBaseMember::getUserId, userId));

        // 4. 可见性判定：成员 → 返回带 myRole；非成员但 PUBLIC → 返回 myRole=null。
        //    非成员且 PRIVATE → 404（隐私保护：不泄露知识库存在性，而非明确告知无权访问），
        //    同样回填空值缓存（该用户对该知识库的查询短时间不再打数据库）。
        KnowledgeBaseVO vo;
        if (myMembership != null) {
            vo = KnowledgeBaseVO.from(knowledgeBase, myMembership.getMemberRole());
        } else if (knowledgeBase.getAccessMode() == KnowledgeBaseAccessMode.PUBLIC) {
            vo = KnowledgeBaseVO.from(knowledgeBase, null);
        } else {
            putCache(cacheKey, CACHE_NULL_MARKER,
                    Duration.ofSeconds(KnowledgeBaseCacheKeys.EMPTY_TTL_SECONDS));
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // ==================== 回填缓存 ====================
        // 正常结果用基础 TTL + 随机抖动（防雪崩）。
        putCache(cacheKey, jsonMapper.writeValueAsString(vo),
                KnowledgeBaseCacheKeys.randomTtl(KnowledgeBaseCacheKeys.DETAIL_TTL_SECONDS));
        return vo;
    }

    @Override
    @Transactional
    public KnowledgeBaseMemberVO addKnowledgeBaseMember(Long userId, Long enterpriseId, Long knowledgeBaseId,
                                                        KnowledgeBaseMemberAddRequest request) {
        // 1. 先校验操作者为知识库管理员（两级管理员之一）。
        requireKnowledgeBaseManager(userId, enterpriseId, knowledgeBaseId);

        // 2. 目标用户必须是该企业正常成员。数据库复合外键 (enterprise_id, user_id)
        //    引用 enterprise_member 会兜底约束，业务层先校验以给出友好错误（403）。
        Long targetUserId = request.getUserId();
        membershipChecker.requireActiveMember(targetUserId, enterpriseId);

        // 3. 查重：该知识库已存在该用户成员记录 → 409（唯一键 uk_ 兜底冲突语义）。
        boolean alreadyExists = knowledgeBaseMemberMapper.exists(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeBaseMember::getUserId, targetUserId));
        if (alreadyExists) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_MEMBER_ALREADY_EXISTS);
        }

        // 4. 插入成员记录，时间统一取 Clock 冻结值。
        Instant now = clock.instant();
        KnowledgeBaseMember member = new KnowledgeBaseMember();
        member.setKnowledgeBaseId(knowledgeBaseId);
        member.setEnterpriseId(enterpriseId);
        member.setUserId(targetUserId);
        member.setMemberRole(request.getMemberRole());
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        knowledgeBaseMemberMapper.insert(member);

        log.info("event=knowledge_base_member_added enterpriseId={} knowledgeBaseId={} memberUserId={} operatorId={}",
                enterpriseId, knowledgeBaseId, targetUserId, userId);

        // ==================== Cache Aside 写路径：成员变化 → 失效列表 + 详情缓存 ====================
        // 成员变动会改变<strong>多个用户</strong>的可见性（新成员加入后该库进入其列表；
        // 移除后从列表中消失）与详情 myRole。列表/详情缓存按「用户维度」分键，受影响
        // 的用户无法逐一枚举，所以用通配 pattern 整组失效该企业在列表缓存、该库的详情
        // 缓存——下个请求自然回源数据库重建。成员操作低频，全量失效的成本可忽略。
        evictListCachesForEnterprise(enterpriseId);
        evictDetailCachesForKnowledgeBase(enterpriseId, knowledgeBaseId);
        return KnowledgeBaseMemberVO.from(member);
    }

    @Override
    @Transactional
    public KnowledgeBaseMemberVO updateKnowledgeBaseMemberRole(Long userId, Long enterpriseId, Long knowledgeBaseId,
                                                               Long targetUserId,
                                                               KnowledgeBaseMemberRoleUpdateRequest request) {
        // 1. 先校验操作者为知识库管理员（两级管理员之一）。
        requireKnowledgeBaseManager(userId, enterpriseId, knowledgeBaseId);

        // 2. 不能修改自己的角色：防止管理员把自己降级后失去管理权。
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_SELF_OPERATION_NOT_ALLOWED);
        }

        // 3. 用「knowledgeBaseId + userId」精确定位目标成员记录；不存在 → 404。
        KnowledgeBaseMember target = knowledgeBaseMemberMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeBaseMember::getUserId, targetUserId));
        if (target == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_MEMBER_NOT_FOUND);
        }

        // 4. 更新角色与更新时间。目标用户的「企业成员」身份在此不做校验：改角色/移除不涉及
        //    新增成员，无需确认目标是否仍是企业正常成员（复合外键只约束插入时）。
        Instant updatedAt = clock.instant();
        int updatedCount = knowledgeBaseMemberMapper.update(null,
                new LambdaUpdateWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeBaseMember::getUserId, targetUserId)
                        .set(KnowledgeBaseMember::getMemberRole, request.getMemberRole())
                        .set(KnowledgeBaseMember::getUpdatedAt, updatedAt));
        if (updatedCount != 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_MEMBER_NOT_FOUND);
        }

        target.setMemberRole(request.getMemberRole());
        target.setUpdatedAt(updatedAt);
        log.info("event=knowledge_base_member_role_updated enterpriseId={} knowledgeBaseId={} "
                        + "memberUserId={} newRole={} operatorId={}",
                enterpriseId, knowledgeBaseId, targetUserId, request.getMemberRole(), userId);

        // 角色变化同样影响目标用户对该库的 myRole 与可见性：整组失效（理由同成员新增）。
        evictListCachesForEnterprise(enterpriseId);
        evictDetailCachesForKnowledgeBase(enterpriseId, knowledgeBaseId);
        return KnowledgeBaseMemberVO.from(target);
    }

    @Override
    @Transactional
    public void removeKnowledgeBaseMember(Long userId, Long enterpriseId, Long knowledgeBaseId, Long targetUserId) {
        // 1. 先校验操作者为知识库管理员（两级管理员之一）。
        requireKnowledgeBaseManager(userId, enterpriseId, knowledgeBaseId);

        // 2. 不能移除自己：防止管理员把自己移出后失去管理权。
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_SELF_OPERATION_NOT_ALLOWED);
        }

        // 3. 用「knowledgeBaseId + userId」精确定位并物理删除成员记录；不存在 → 404。
        int deletedCount = knowledgeBaseMemberMapper.delete(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeBaseMember::getUserId, targetUserId));
        if (deletedCount != 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_MEMBER_NOT_FOUND);
        }

        // 成员被移除：该用户列表不再显示此库、其他成员的 myRole 判断也受影响，整组失效
        // （理由同成员新增）。
        evictListCachesForEnterprise(enterpriseId);
        evictDetailCachesForKnowledgeBase(enterpriseId, knowledgeBaseId);
        log.info("event=knowledge_base_member_removed enterpriseId={} knowledgeBaseId={} "
                        + "memberUserId={} operatorId={}",
                enterpriseId, knowledgeBaseId, targetUserId, userId);
    }

    @Override
    @Transactional
    public KnowledgeBaseVO updateKnowledgeBase(Long userId, Long enterpriseId, Long knowledgeBaseId,
                                               KnowledgeBaseUpdateRequest request) {
        // 1. 先校验操作者为知识库管理员（两级管理员之一）；同时确认知识库存在且 NORMAL。
        KnowledgeBase target = requireKnowledgeBaseManager(userId, enterpriseId, knowledgeBaseId);

        // 2. 更新 name/description/accessMode/updatedAt（PUT 全量语义）。名称去首尾空白；
        //    accessMode 由 DTO 保证非空（全量更新必须显式声明）。用「id + enterpriseId」
        //    组合更新，防止跨企业操作。
        Instant updatedAt = clock.instant();
        String name = request.getName().strip();
        int updatedCount = knowledgeBaseMapper.update(null,
                new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getEnterpriseId, enterpriseId)
                        .set(KnowledgeBase::getName, name)
                        .set(KnowledgeBase::getDescription, request.getDescription())
                        .set(KnowledgeBase::getAccessMode, request.getAccessMode())
                        .set(KnowledgeBase::getUpdatedAt, updatedAt));
        if (updatedCount != 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        // 3. 基于已校验实体回填，返回更新后的 VO。
        target.setName(name);
        target.setDescription(request.getDescription());
        target.setAccessMode(request.getAccessMode());
        target.setUpdatedAt(updatedAt);

        // ==================== Cache Aside 写路径：更新 → 失效列表 + 详情缓存 ====================
        // 改名/改描述/改 accessMode 都会改变列表显示内容与详情数据（accessMode 变化还会
        // 影响「哪些用户可见」——PUBLIC 改 PRIVATE 后非成员应不再可见），列表与详情
        // 缓存都必须整组失效，让所有用户下次查询回源数据库。
        evictListCachesForEnterprise(enterpriseId);
        evictDetailCachesForKnowledgeBase(enterpriseId, knowledgeBaseId);
        log.info("event=knowledge_base_updated enterpriseId={} knowledgeBaseId={} operatorId={}",
                enterpriseId, knowledgeBaseId, userId);
        return KnowledgeBaseVO.from(target);
    }

    @Override
    @Transactional
    public void deleteKnowledgeBase(Long userId, Long enterpriseId, Long knowledgeBaseId) {
        // 1. 先校验操作者为知识库管理员（两级管理员之一）；同时确认知识库存在且 NORMAL。
        requireKnowledgeBaseManager(userId, enterpriseId, knowledgeBaseId);

        // 2. 同一事务内先删全部成员记录，再物理删除知识库（顺序先子后父）。
        //    外键未设 ON DELETE CASCADE，必须手动级联删除 knowledge_base_member。
        knowledgeBaseMemberMapper.delete(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId));
        int deletedCount = knowledgeBaseMapper.delete(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getEnterpriseId, enterpriseId));
        if (deletedCount != 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        // 删除后该库从所有用户列表与详情中消失：列表 + 详情缓存整组失效。
        evictListCachesForEnterprise(enterpriseId);
        evictDetailCachesForKnowledgeBase(enterpriseId, knowledgeBaseId);
        log.info("event=knowledge_base_deleted enterpriseId={} knowledgeBaseId={} operatorId={}",
                enterpriseId, knowledgeBaseId, userId);
    }

    @Override
    @Transactional
    public KnowledgeBaseVO updateKnowledgeBaseStatus(Long userId, Long enterpriseId, Long knowledgeBaseId,
                                                     KnowledgeBaseStatusUpdateRequest request) {
        // 1. 先校验操作者为知识库管理员（两级管理员之一）；同时确认知识库存在且 NORMAL。
        KnowledgeBase target = requireKnowledgeBaseManager(userId, enterpriseId, knowledgeBaseId);

        // 2. 同状态请求幂等返回，不产生无意义写入和更新时间变化。
        //    数据没有变化，缓存依然正确，因此幂等分支不失效缓存。
        KnowledgeBaseStatus targetStatus = request.getStatus();
        if (target.getStatus() == targetStatus) {
            return KnowledgeBaseVO.from(target);
        }

        // 3. 更新状态与更新时间。
        Instant updatedAt = clock.instant();
        int updatedCount = knowledgeBaseMapper.update(null,
                new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getEnterpriseId, enterpriseId)
                        .set(KnowledgeBase::getStatus, targetStatus)
                        .set(KnowledgeBase::getUpdatedAt, updatedAt));
        if (updatedCount != 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        target.setStatus(targetStatus);
        target.setUpdatedAt(updatedAt);

        // 状态变更（NORMAL ↔ DISABLED）直接决定「用户可见性」（DISABLED 一律不可见）：
        // 列表与详情缓存整组失效，让可见性变化立即生效。
        evictListCachesForEnterprise(enterpriseId);
        evictDetailCachesForKnowledgeBase(enterpriseId, knowledgeBaseId);
        log.info("event=knowledge_base_status_updated enterpriseId={} knowledgeBaseId={} "
                        + "newStatus={} operatorId={}",
                enterpriseId, knowledgeBaseId, targetStatus, userId);
        return KnowledgeBaseVO.from(target);
    }

    // ==================== 缓存私有方法（Cache Aside 容错要点） ====================
    //
    // 所有缓存读写都包 try-catch 降级：缓存是「加速」而不是「正确性依赖」——
    // Redis 故障时查询降级为直接走数据库（多一次 DB 查询而已），业务照常返回，
    // 绝不能让缓存故障拖垮主链路。这是 Cache Aside 与业务数据隔离的容错底线，
    // 缓存侧的异常只记 WARN（日志白名单：只记键名与异常类型名，不记缓存内容）。

    /**
     * 读缓存（GET）：Redis 异常时返回 {@code null}，调用方按未命中处理并回源数据库。
     */
    private String getCache(String cacheKey) {
        try {
            return redisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException ex) {
            log.warn("event=kb_cache_read_failed cacheKey={} errorType={}",
                    cacheKey, ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 写缓存（SET + TTL）：Redis 异常时只记 WARN，不影响已完成的数据库操作。
     */
    private void putCache(String cacheKey, String json, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(cacheKey, json, ttl);
        } catch (RuntimeException ex) {
            log.warn("event=kb_cache_write_failed cacheKey={} errorType={}",
                    cacheKey, ex.getClass().getSimpleName());
        }
    }

    /**
     * 失效某企业下全部用户的列表缓存（写路径统一入口）。
     *
     * <p>为什么要整组删而不是一条条删：列表缓存按「用户 × 企业」分键，写操作影响的
     * 用户集合无法精确枚举（如 PUBLIC 库对全企业成员可见），只能按通配 pattern
     * 匹配这批键统一删除。</p>
     *
     * <p><strong>用 Key 通配而非逐键</strong>：{@code keys(pattern)} 会遍历全库匹配
     * （生产大 Key 场景有性能风险，真实系统用 {@code SCAN} 游标迭代分批删除；
     * 教学阶段键量小、删除低频，用 keys 直截了当——注释里说明两者的取舍）。</p>
     */
    private void evictListCachesForEnterprise(Long enterpriseId) {
        evictByPattern(KnowledgeBaseCacheKeys.listPattern(enterpriseId));
    }

    /**
     * 失效某知识库下全部用户的详情缓存（写路径统一入口），理由同列表。
     */
    private void evictDetailCachesForKnowledgeBase(Long enterpriseId, Long knowledgeBaseId) {
        evictByPattern(KnowledgeBaseCacheKeys.detailPattern(enterpriseId, knowledgeBaseId));
    }

    /** 按通配 pattern 批量删缓存键；Redis 异常时只记 WARN 降级。 */
    private void evictByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (RuntimeException ex) {
            log.warn("event=kb_cache_evict_failed pattern={} errorType={}",
                    pattern, ex.getClass().getSimpleName());
        }
    }

    /**
     * 统一两级管理员校验：当前用户必须是知识库资源的管理者。
     *
     * <p>校验顺序（先资源后权限，减少对数据库的无效查询）：</p>
     * <ol>
     *   <li>企业存在（404）；正常成员身份（403）——先资源后权限；</li>
     *   <li>按「id + enterpriseId」组合定位知识库，不存在或 status != NORMAL → 404
     *       （禁用与不存在同样处理，不泄露状态）；</li>
     *   <li>查当前用户对该知识库的成员记录，memberRole==ADMIN → 资源级管理员，通过；</li>
     *   <li>否则查企业角色：enterpriseMemberMapper.selectOne(userId+enterpriseId) 取 roleId
     *       → enterpriseRoleMapper.selectById 取 code，code ∈ (OWNER, ADMIN) → 企业级管理员，通过；</li>
     *   <li>都不满足 → 403。</li>
     * </ol>
     *
     * <p><strong>为什么同时看资源级与企业级：</strong>知识库管理者不一定必须是知识库成员——
     * 企业 OWNER/ADMIN 应能管理企业内所有知识库，包括自己未加入的；反过来，知识库 ADMIN
     * 即使不是企业管理者也应能管理该知识库。两级取或，缺一不可。</p>
     *
     * @return 校验通过后返回目标知识库实体（已确认存在且 NORMAL），供调用方复用避免二次查询
     */
    private KnowledgeBase requireKnowledgeBaseManager(Long userId, Long enterpriseId, Long knowledgeBaseId) {
        // 1. 先资源后权限：企业必须存在，操作者必须是该企业正常成员。
        membershipChecker.requireEnterprise(enterpriseId);
        membershipChecker.requireActiveMember(userId, enterpriseId);

        // 2. 用「id + enterpriseId」组合定位知识库。禁用与不存在同样按 404 处理，不泄露状态。
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getEnterpriseId, enterpriseId));
        if (knowledgeBase == null || knowledgeBase.getStatus() != KnowledgeBaseStatus.NORMAL) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        // 3. 资源级：当前用户是该知识库 ADMIN 成员。
        boolean isResourceAdmin = knowledgeBaseMemberMapper.exists(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeBaseMember::getUserId, userId)
                        .eq(KnowledgeBaseMember::getMemberRole, KnowledgeBaseMemberRole.ADMIN));
        if (isResourceAdmin) {
            return knowledgeBase;
        }

        // 4. 企业级：企业 OWNER/ADMIN 可管理企业内所有知识库。先取成员关系得到 roleId，
        //    再解析角色编码；roleId 为空或角色不存在/禁用均视为无企业级管理权。
        EnterpriseMember enterpriseMember = enterpriseMemberMapper.selectOne(
                new LambdaQueryWrapper<EnterpriseMember>()
                        .eq(EnterpriseMember::getEnterpriseId, enterpriseId)
                        .eq(EnterpriseMember::getUserId, userId));
        if (enterpriseMember != null && enterpriseMember.getRoleId() != null) {
            EnterpriseRole role = enterpriseRoleMapper.selectById(enterpriseMember.getRoleId());
            if (role != null && role.getStatus() == EnterpriseRoleStatus.NORMAL
                    && ENTERPRISE_MANAGER_ROLES.contains(role.getCode())) {
                return knowledgeBase;
            }
        }

        // 5. 都不满足 → 无管理权限。
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }
}
