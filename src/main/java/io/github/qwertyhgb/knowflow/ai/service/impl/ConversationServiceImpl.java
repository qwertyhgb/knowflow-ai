package io.github.qwertyhgb.knowflow.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.qwertyhgb.knowflow.ai.entity.AiMessage;
import io.github.qwertyhgb.knowflow.ai.entity.Conversation;
import io.github.qwertyhgb.knowflow.ai.mapper.AiMessageMapper;
import io.github.qwertyhgb.knowflow.ai.mapper.ConversationMapper;
import io.github.qwertyhgb.knowflow.ai.service.ConversationService;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationDetailVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationMessageVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * AI 会话管理服务实现。
 *
 * <p>会话是<strong>用户级资源</strong>：所有操作都以 userId 为归属判据。
 * 访问控制策略：只能操作自己的会话，<strong>他人会话与不存在的会话统一按 404</strong>
 * （CONVERSATION_NOT_FOUND）处理——不区分「不存在」与「无权访问」，避免泄露
 * 他人会话的存在性（与知识库 PRIVATE 不可见时返回 404 的隐私设计一致）。</p>
 */
@Slf4j
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final String DEFAULT_TITLE = "新对话";

    private final ConversationMapper conversationMapper;
    private final AiMessageMapper aiMessageMapper;
    private final Clock clock;

    public ConversationServiceImpl(ConversationMapper conversationMapper,
                                   AiMessageMapper aiMessageMapper,
                                   Clock clock) {
        this.conversationMapper = conversationMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.clock = clock;
    }

    @Override
    public ConversationVO createConversation(Long userId) {
        Instant now = clock.instant();
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        // 【为什么创建时不生成标题？】还没有任何对话内容，无信息可生成。
        // 标题等第一条用户消息到达后，用首条消息前 20 字符自动生成
        // （见 ConversationChatServiceImpl 的标题生成逻辑），创建时先用默认值占位。
        conversation.setTitle(DEFAULT_TITLE);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);

        log.info("event=conversation_created userId={} conversationId={}", userId, conversation.getId());
        return ConversationVO.from(conversation);
    }

    @Override
    public List<ConversationVO> listMyConversations(Long userId) {
        // 【为什么按 updated_at 倒序？】会话列表展示「最近活跃优先」与主流聊天产品一致；
        // updated_at 在每次发消息时更新（见 ConversationChatServiceImpl），因此
        // 列表顺序天然反映「哪个会话最近被使用」。
        List<Conversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .orderByDesc(Conversation::getUpdatedAt));
        return conversations.stream().map(ConversationVO::from).toList();
    }

    @Override
    public ConversationDetailVO getConversationDetail(Long userId, Long conversationId) {
        Conversation conversation = requireOwnedConversation(userId, conversationId);
        // 会话详情消息按创建时间升序：从头到尾阅读历史，与聊天产品一致。
        List<AiMessage> messages = aiMessageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, conversationId)
                        .orderByAsc(AiMessage::getCreatedAt));
        List<ConversationMessageVO> messageVOs = messages.stream()
                .map(ConversationMessageVO::from)
                .toList();
        return ConversationDetailVO.of(
                conversation.getId(), conversation.getTitle(),
                conversation.getCreatedAt(), conversation.getUpdatedAt(),
                messageVOs);
    }

    @Override
    public void deleteConversation(Long userId, Long conversationId) {
        // 先校验归属再删除：未授权（含不存在）直接 404，避免误删他人会话。
        requireOwnedConversation(userId, conversationId);

        // 【为什么先删消息再删会话？】V11/V12 的表设计刻意未建物理外键级联
        // （理由见迁移脚本注释）。数据库没有级联约束时，删除顺序很重要：
        // 必须先删「引用会话的子记录」（ai_message），再删「被引用的会话记录」，
        // 否则会留下孤儿消息。教学阶段显式两步删除，顺序即正确性。
        aiMessageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);

        log.info("event=conversation_deleted userId={} conversationId={}", userId, conversationId);
    }

    /**
     * 校验会话归属并返回会话实体；非本人或不存在统一抛 404。
     *
     * <p>【为什么「查不到」与「不是我的」返回同一个错误？】
     * 若分别返回 404 与 403，攻击者就能通过对比响应区分「某个会话存在但属于别人」
     * 与「会话不存在」，从而探测他人会话 ID 的存在性。统一 404 让两种情况不可区分。</p>
     */
    private Conversation requireOwnedConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            log.warn("event=conversation_access_denied userId={} conversationId={}", userId, conversationId);
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }
}
