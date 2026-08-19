package io.github.qwertyhgb.knowflow.knowledge.controller;

import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.audit.annotation.OperationLog;
import io.github.qwertyhgb.knowflow.common.response.Result;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseCreateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseMemberAddRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseMemberRoleUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseStatusUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.dto.request.KnowledgeBaseUpdateRequest;
import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBase;
import io.github.qwertyhgb.knowflow.knowledge.service.KnowledgeBaseService;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseMemberVO;
import io.github.qwertyhgb.knowflow.knowledge.vo.KnowledgeBaseVO;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库接口。
 *
 * <p>接口位于企业作用域路径，必须登录并携带与路径一致的 {@code X-Enterprise-Id}
 * 请求头；企业上下文与成员身份由 {@code EnterpriseContextFilter} 在进入控制器前统一校验。</p>
 *
 * <p><strong>权限模型（为什么本接口不加 {@code @PreAuthorize}）：</strong>知识库采用
 * 「资源级成员角色」的权限分层——<strong>企业级权限码</strong>（{@code @PreAuthorize} +
 * authorities）表达「能否做某类操作」，但<strong>权限码无法携带资源 ID</strong>；
 * 而创建知识库本身是在产生一个新的资源，此时尚不存在「该资源的成员身份」。因此创建
 * 知识库是「企业正常成员的基础能力」，只需要成员身份（由过滤器保证），
 * 知识库内的更新/删除/成员管理等操作将由后续 Service 按资源成员角色校验。</p>
 *
 * <p><strong>可见性与详情查询同样不加 {@code @PreAuthorize}</strong>：查询可见知识库/查详情
 * 也是企业正常成员的基础能力，具体到「某个知识库能否看」由 Service 按资源级可见性规则
 * （PUBLIC ∪ 成员身份）判定，与创建接口的权限分层模型保持一致。</p>
 *
 * <p><strong>管理接口也不加 {@code @PreAuthorize}</strong>：知识库管理操作（成员管理/更新/
 * 删除/状态）要求「两级管理员」之一——知识库 ADMIN 成员，或企业 OWNER/ADMIN。这些判断依赖
 * 资源 ID 与资源级成员角色，权限码携带不了资源 ID，故统一由 Service 的
 * {@code requireKnowledgeBaseManager} 在资源级校验，Controller 只做参数接收与 VO 转换。</p>
 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 创建知识库。
     *
     * <p>仅需企业正常成员身份即可调用，Service 内部再次校验以确保安全。
     * 返回状态码沿用现有创建接口约定（成功 200，通过 {@link Result} 包装）。</p>
     */
    @PostMapping
    @OperationLog("创建知识库")
    public Result<KnowledgeBaseVO> createKnowledgeBase(Authentication authentication,
                                                       @PathVariable Long enterpriseId,
                                                       @Valid @RequestBody KnowledgeBaseCreateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        KnowledgeBase knowledgeBase = knowledgeBaseService.createKnowledgeBase(userId, enterpriseId, request);
        return Result.success(KnowledgeBaseVO.from(knowledgeBase));
    }

    /**
     * 查询当前用户在指定企业中可见的知识库列表。
     *
     * <p><strong>可见性规则：</strong>知识库可见 = 企业内所有正常成员可见（accessMode=PUBLIC），
     * 或 当前用户是其成员（含 myRole）。仅需企业正常成员身份即可访问，不加 {@code @PreAuthorize}。</p>
     */
    @GetMapping
    public Result<List<KnowledgeBaseVO>> listVisibleKnowledgeBases(
            Authentication authentication,
            @PathVariable Long enterpriseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(knowledgeBaseService.listVisibleKnowledgeBases(userId, enterpriseId));
    }

    /**
     * 查询单个知识库详情。
     *
     * <p><strong>隐私保护设计：</strong>PRIVATE 知识库对非成员返回 404 而非 403——不泄露
     * 「这个知识库存在」这一事实（与邀请模块「不区分查无此人 / 不是本企业邀请」同一思路）。
     * 当前用户是其成员则返回带 myRole 的 VO；PUBLIC 非成员返回 myRole 为 {@code null} 的 VO。</p>
     */
    @GetMapping("/{knowledgeBaseId}")
    public Result<KnowledgeBaseVO> getKnowledgeBase(Authentication authentication,
                                                    @PathVariable Long enterpriseId,
                                                    @PathVariable Long knowledgeBaseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(knowledgeBaseService.getKnowledgeBase(userId, enterpriseId, knowledgeBaseId));
    }

    /**
     * 向知识库添加成员。
     *
     * <p><strong>权限：</strong>需知识库 ADMIN 或企业 OWNER/ADMIN（由 Service 按资源级校验）。
     * 目标用户须为该企业正常成员且非已有成员。</p>
     */
    @PostMapping("/{knowledgeBaseId}/members")
    public Result<KnowledgeBaseMemberVO> addKnowledgeBaseMember(
            Authentication authentication,
            @PathVariable Long enterpriseId,
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody KnowledgeBaseMemberAddRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(knowledgeBaseService.addKnowledgeBaseMember(
                userId, enterpriseId, knowledgeBaseId, request));
    }

    /**
     * 修改知识库成员角色。
     *
     * <p><strong>权限：</strong>需知识库 ADMIN 或企业 OWNER/ADMIN（由 Service 按资源级校验）。
     * 不能修改自己的角色。</p>
     */
    @PutMapping("/{knowledgeBaseId}/members/{targetUserId}/role")
    public Result<KnowledgeBaseMemberVO> updateKnowledgeBaseMemberRole(
            Authentication authentication,
            @PathVariable Long enterpriseId,
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long targetUserId,
            @Valid @RequestBody KnowledgeBaseMemberRoleUpdateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(knowledgeBaseService.updateKnowledgeBaseMemberRole(
                userId, enterpriseId, knowledgeBaseId, targetUserId, request));
    }

    /**
     * 移除知识库成员（物理删除成员记录）。
     *
     * <p><strong>权限：</strong>需知识库 ADMIN 或企业 OWNER/ADMIN（由 Service 按资源级校验）。
     * 不能移除自己。</p>
     */
    @DeleteMapping("/{knowledgeBaseId}/members/{targetUserId}")
    public Result<Void> removeKnowledgeBaseMember(Authentication authentication,
                                                  @PathVariable Long enterpriseId,
                                                  @PathVariable Long knowledgeBaseId,
                                                  @PathVariable Long targetUserId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        knowledgeBaseService.removeKnowledgeBaseMember(userId, enterpriseId, knowledgeBaseId, targetUserId);
        return Result.success();
    }

    /**
     * 更新知识库（PUT 全量语义：名称、描述、访问模式）。
     *
     * <p><strong>权限：</strong>需知识库 ADMIN 或企业 OWNER/ADMIN（由 Service 按资源级校验）。</p>
     */
    @PutMapping("/{knowledgeBaseId}")
    public Result<KnowledgeBaseVO> updateKnowledgeBase(Authentication authentication,
                                                       @PathVariable Long enterpriseId,
                                                       @PathVariable Long knowledgeBaseId,
                                                       @Valid @RequestBody KnowledgeBaseUpdateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(knowledgeBaseService.updateKnowledgeBase(
                userId, enterpriseId, knowledgeBaseId, request));
    }

    /**
     * 删除知识库（级联删除其全部成员记录）。
     *
     * <p><strong>权限：</strong>需知识库 ADMIN 或企业 OWNER/ADMIN（由 Service 按资源级校验）。</p>
     */
    @DeleteMapping("/{knowledgeBaseId}")
    @OperationLog("删除知识库")
    public Result<Void> deleteKnowledgeBase(Authentication authentication,
                                            @PathVariable Long enterpriseId,
                                            @PathVariable Long knowledgeBaseId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        knowledgeBaseService.deleteKnowledgeBase(userId, enterpriseId, knowledgeBaseId);
        return Result.success();
    }

    /**
     * 启用或禁用知识库。
     *
     * <p><strong>权限：</strong>需知识库 ADMIN 或企业 OWNER/ADMIN（由 Service 按资源级校验）。</p>
     */
    @PutMapping("/{knowledgeBaseId}/status")
    public Result<KnowledgeBaseVO> updateKnowledgeBaseStatus(
            Authentication authentication,
            @PathVariable Long enterpriseId,
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody KnowledgeBaseStatusUpdateRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(knowledgeBaseService.updateKnowledgeBaseStatus(
                userId, enterpriseId, knowledgeBaseId, request));
    }
}
