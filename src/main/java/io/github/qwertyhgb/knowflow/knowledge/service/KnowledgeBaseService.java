package io.github.qwertyhgb.knowflow.knowledge.service;

import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseCreateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseMemberAddRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseMemberRoleUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBase;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseMemberVO;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseVO;

import java.util.List;

/**
 * 知识库业务服务。
 */
public interface KnowledgeBaseService {

    /**
     * 在指定企业中创建知识库。
     *
     * <p><strong>权限模型：</strong>创建知识库是「企业正常成员的基础能力」，本方法在
     * Service 层只校验企业存在与成员身份，不校验企业级权限码（Controller 也不加
     * {@code @PreAuthorize}）。这是因为权限码无法携带资源 ID，而创建知识库本身就是
     * 在产生一个新的资源——资源级权限将由后续的知识库成员角色（member_role）控制。</p>
     *
     * <p>创建知识库与写入创建者成员记录在同一事务内完成：创建者自动成为该知识库的
     * ADMIN 成员，这是资源级权限的初始事实。</p>
     *
     * @param userId       当前登录用户 ID（将作为知识库创建者与初始成员）
     * @param enterpriseId 目标企业 ID
     * @param request      创建知识库请求参数
     * @return 创建成功的知识库实体（供 Controller 转换为 VO，不直接返回给客户端）
     */
    KnowledgeBase createKnowledgeBase(Long userId, Long enterpriseId, KnowledgeBaseCreateRequest request);

    /**
     * 查询当前用户在指定企业中「可见」的知识库列表。
     *
     * <p><strong>可见性规则（两层取并集）：</strong>知识库对当前用户可见当且仅当
     * {@code access_mode=PUBLIC}（企业内所有正常成员可见）<em>或</em> 当前用户是其成员
     * （{@code knowledge_base_member} 存在记录）。列表返回满足可见性的知识库，
     * 每项的 {@code myRole} 按用户成员记录标注（PUBLIC 的非成员为 {@code null}）；
     * PRIVATE 且非成员的知识库被过滤掉，不泄露其存在性（与 {@code getKnowledgeBase}
     * 的隐私设计一致）。</p>
     *
     * <p>空列表返回 {@code List.of()}，不返回 {@code null}；无知识库时返回空列表。</p>
     *
     * @param userId       当前登录用户 ID
     * @param enterpriseId 目标企业 ID
     * @return 可见知识库 VO 列表（含 myRole），按创建时间倒序返回
     */
    List<KnowledgeBaseVO> listVisibleKnowledgeBases(Long userId, Long enterpriseId);

    /**
     * 查询单个知识库详情，按可见性规则决定当前用户是否可读。
     *
     * <p><strong>可见性规则：</strong>当前用户是其成员 → 返回（带 myRole）；
     * 非成员但 {@code access_mode=PUBLIC} → 返回（myRole 为 null）；
     * 非成员且 {@code access_mode=PRIVATE} → 404。</p>
     *
     * <p><strong>隐私保护设计（404 vs 403）：</strong>PRIVATE 知识库对非成员返回
     * <strong>404 而非 403</strong>——不泄露「这个知识库存在」这一事实。这与邀请模块
     * 「不区分查无此人 / 不是本企业邀请」是同一思路：对无权访问的资源表现得「不存在」，
     * 避免攻击者仅凭枚举 ID 判断出敏感知识库是否存在。知识库被禁用（DISABLED）也同样
     * 按不存在处理，不泄露状态信息。</p>
     *
     * @param userId          当前登录用户 ID
     * @param enterpriseId    目标企业 ID
     * @param knowledgeBaseId 目标知识库 ID
     * @return 知识库详情 VO（可能带 myRole）
     */
    KnowledgeBaseVO getKnowledgeBase(Long userId, Long enterpriseId, Long knowledgeBaseId);

    /**
     * 向指定知识库添加成员。
     *
     * <p><strong>权限：</strong>需要知识库 ADMIN 或企业 OWNER/ADMIN（两级管理员取或），
     * 由 Service 按资源级校验（权限码无法携带资源 ID，故不加 {@code @PreAuthorize}）。
     * 目标用户须为该企业正常成员，且不得是已有知识库成员。</p>
     *
     * @return 新增的知识库成员 VO
     */
    KnowledgeBaseMemberVO addKnowledgeBaseMember(Long userId, Long enterpriseId, Long knowledgeBaseId,
                                                 KnowledgeBaseMemberAddRequest request);

    /**
     * 修改指定知识库成员的成员角色。
     *
     * <p><strong>权限：</strong>同上需两级管理员之一。管理员不能修改自己的角色
     * （防止把自己降级后失去管理权）。目标成员记录不存在时返回 404。</p>
     *
     * @return 修改后的知识库成员 VO
     */
    KnowledgeBaseMemberVO updateKnowledgeBaseMemberRole(Long userId, Long enterpriseId, Long knowledgeBaseId,
                                                       Long targetUserId, KnowledgeBaseMemberRoleUpdateRequest request);

    /**
     * 移除指定知识库成员（物理删除成员记录）。
     *
     * <p><strong>权限：</strong>同上需两级管理员之一。管理员不能移除自己。目标成员记录
     * 不存在时返回 404。</p>
     */
    void removeKnowledgeBaseMember(Long userId, Long enterpriseId, Long knowledgeBaseId, Long targetUserId);

    /**
     * 更新知识库（PUT 全量语义：名称、描述、访问模式一并更新）。
     *
     * <p><strong>权限：</strong>需两级管理员之一。知识库禁用或不存在时返回 404
     * （不泄露状态）。</p>
     *
     * @return 更新后的知识库 VO
     */
    KnowledgeBaseVO updateKnowledgeBase(Long userId, Long enterpriseId, Long knowledgeBaseId,
                                        KnowledgeBaseUpdateRequest request);

    /**
     * 删除知识库（同一事务内先删除全部成员记录，再物理删除知识库）。
     *
     * <p><strong>权限：</strong>需两级管理员之一。外键未设 ON DELETE CASCADE，
     * 必须手动级联删除成员记录，顺序先子后父。</p>
     */
    void deleteKnowledgeBase(Long userId, Long enterpriseId, Long knowledgeBaseId);

    /**
     * 启用或禁用知识库。
     *
     * <p><strong>权限：</strong>需两级管理员之一。同状态请求幂等返回，不产生无意义写入。</p>
     *
     * @return 状态更新后的知识库 VO
     */
    KnowledgeBaseVO updateKnowledgeBaseStatus(Long userId, Long enterpriseId, Long knowledgeBaseId,
                                              KnowledgeBaseStatusUpdateRequest request);
}
