package io.github.qwertyhgb.knowflow.ai.service;

import io.github.qwertyhgb.knowflow.ai.vo.ConversationDetailVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationVO;

import java.util.List;

/**
 * AI 会话管理服务接口。
 *
 * <p>职责：会话的增删查（创建/列表/详情/删除）。会话是用户级资源，
 * 所有操作都以「当前登录用户 ID」为归属判据——只能操作自己的会话，
 * 他人会话与不存在的会话统一按 404 处理（不泄露存在性）。</p>
 */
public interface ConversationService {

    /**
     * 创建会话：插入 title 为默认值「新对话」的会话记录。
     *
     * @param userId 当前登录用户 ID
     * @return 新建会话的响应 VO
     */
    ConversationVO createConversation(Long userId);

    /**
     * 列出当前用户的全部会话，按最近活跃（updated_at）倒序。
     *
     * @param userId 当前登录用户 ID
     * @return 会话列表（最近活跃优先）
     */
    List<ConversationVO> listMyConversations(Long userId);

    /**
     * 获取会话详情（含全部消息，按创建时间升序）。
     *
     * @param userId         当前登录用户 ID
     * @param conversationId 目标会话 ID
     * @return 会话详情（会话信息 + 消息列表）
     */
    ConversationDetailVO getConversationDetail(Long userId, Long conversationId);

    /**
     * 删除会话：先删其全部消息，再删会话本身。
     *
     * @param userId         当前登录用户 ID
     * @param conversationId 目标会话 ID
     */
    void deleteConversation(Long userId, Long conversationId);
}
