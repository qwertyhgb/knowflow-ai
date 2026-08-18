package io.github.qwertyhgb.knowflow.ai.service;

import io.github.qwertyhgb.knowflow.ai.vo.ConversationChatVO;
import io.github.qwertyhgb.knowflow.ai.vo.RagChatVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会话内多轮对话服务接口。
 *
 * <p>职责：在指定会话内完成一次「用户消息 → 加载历史上下文 → LLM 回答」的多轮对话，
 * 并把用户消息与 AI 消息都持久化到数据库（多轮记忆的数据底座）。</p>
 */
public interface ConversationChatService {

    /**
     * 会话内对话（同步）。
     *
     * <p>流程：校验会话归属 → 保存用户消息 → 标题自动生成 → 加载历史（滑动窗口）
     * → 构造 Prompt 调 LLM → 保存 AI 消息（含 token 统计）→ 更新会话时间。
     * 详见实现类注释。</p>
     *
     * @param userId         当前登录用户 ID
     * @param conversationId 目标会话 ID
     * @param message        用户消息
     * @return AI 回答 + token 统计
     */
    ConversationChatVO chat(Long userId, Long conversationId, String message);

    /**
     * 会话内 RAG 对话（同步）。
     *
     * <p>流程：校验会话归属 → 保存用户消息 → 标题自动生成 → 加载历史（滑动窗口）
     * → 检索知识库（向量检索 Top K + 阈值过滤）→ 拼装 Prompt（历史 + 检索块 + 问题）
     * → 调 LLM → 保存 AI 消息（含回答与引用 JSON）→ 更新会话时间。</p>
     *
     * <p><strong>会话内 RAG 与 Phase 11 无会话 RAG 的关系</strong>：
     * 会话内 RAG 是「多轮对话 + RAG 检索」的融合——既有历史上下文（多轮连贯性），
     * 又有知识库检索块（事实依据），回答与引用全部落库，历史会话重开仍可见引用。
     * 检索与引用机制完全复用 Phase 11 的 {@link io.github.qwertyhgb.knowflow.ai.service.RagChatService}
     * 逻辑，前端引用卡片渲染也直接复用（返回结构一致）。</p>
     *
     * @param userId         当前登录用户 ID
     * @param conversationId 目标会话 ID
     * @param question       用户问题
     * @param topK           检索条数（1~20）
     * @param scoreThreshold 相似度阈值（0.0~1.0）
     * @return AI 回答（含引用标注）+ 引用来源数组
     */
    RagChatVO ragChat(Long userId, Long conversationId, String question, int topK, double scoreThreshold);

    /**
     * 会话内 RAG 对话（流式）。
     *
     * <p>流程与同步版一致，区别是回答采用 SSE 流式推送（打字机效果）：
     * 先发 citations 命名事件（引用来源 JSON），再逐块发送回答分片（data 事件），
     * 流结束后完整回答落库为 AI 消息。流式版的 token 统计存 NULL（教学阶段简化，
     * 流式 API 拿 usage 需要额外调用，Phase 12 不引入）。</p>
     *
     * <p><strong>为什么 citations 先于回答发出</strong>：前端需要先知道引用来源，
     * 回答中的 [1][2] 引用标注才能即时对应上——否则回答已经流式显示了，引用列表还没到。
     * 命名事件（event: citations）与默认事件（data 分片）共享同一条 SSE 连接，
     * 前端分流处理：addEventListener('citations', ...) 处理引用，onmessage 处理回答分片。</p>
     *
     * @param userId         当前登录用户 ID
     * @param conversationId 目标会话 ID
     * @param question       用户问题
     * @param topK           检索条数（1~20）
     * @param scoreThreshold 相似度阈值（0.0~1.0）
     * @param emitter        SSE 发送器（由 Controller 创建并传入）
     */
    void ragChatStream(Long userId, Long conversationId, String question, int topK, double scoreThreshold, SseEmitter emitter);
}
