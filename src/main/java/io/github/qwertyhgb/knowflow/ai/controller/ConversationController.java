package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.dto.request.ConversationChatRequest;
import io.github.qwertyhgb.knowflow.ai.dto.request.ConversationRagRequest;
import io.github.qwertyhgb.knowflow.ai.service.ConversationChatService;
import io.github.qwertyhgb.knowflow.ai.service.ConversationService;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationChatVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationDetailVO;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationVO;
import io.github.qwertyhgb.knowflow.ai.vo.RagChatVO;
import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.response.Result;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 会话接口（创建/列表/详情/删除 + 会话内对话）。
 *
 * <p>接口均为<strong>用户级</strong>（登录即可用，不依赖企业上下文）——这是
 * 「个人 AI 助手」的形态：会话属于用户个人，不与某个企业绑定。
 * 后续客服场景（Phase 13）再挂企业上下文与知识库。</p>
 *
 * <p>【如何取当前用户 ID？】认证主体是 {@link EnterpriseUser}（Token 认证建立），
 * 从 {@code authentication.getPrincipal()} 取 userId。会话是用户级资源，
 * 全部操作以此 ID 为归属判据。</p>
 *
 * <p>所有接口自动受 Security 保护（全局默认需登录），匿名请求返回 401。</p>
 */
@RestController
@RequestMapping("/api/ai/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationChatService conversationChatService;

    public ConversationController(ConversationService conversationService,
                                  ConversationChatService conversationChatService) {
        this.conversationService = conversationService;
        this.conversationChatService = conversationChatService;
    }

    /**
     * 创建会话。
     *
     * @param authentication 当前登录用户认证信息
     * @return 新建会话（标题为默认值「新对话」）
     */
    @PostMapping
    public Result<ConversationVO> createConversation(Authentication authentication) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(conversationService.createConversation(userId));
    }

    /**
     * 我的会话列表（按最近活跃倒序）。
     *
     * @param authentication 当前登录用户认证信息
     * @return 当前用户的全部会话
     */
    @GetMapping
    public Result<List<ConversationVO>> listConversations(Authentication authentication) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(conversationService.listMyConversations(userId));
    }

    /**
     * 会话详情（含全部消息，按创建时间升序）。
     *
     * @param authentication  当前登录用户认证信息
     * @param conversationId 会话 ID（路径参数，资源定位）
     * @return 会话信息 + 消息列表
     */
    @GetMapping("/{conversationId}")
    public Result<ConversationDetailVO> getConversationDetail(Authentication authentication,
                                                              @PathVariable Long conversationId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        return Result.success(conversationService.getConversationDetail(userId, conversationId));
    }

    /**
     * 删除会话（级联删除其全部消息）。
     *
     * @param authentication  当前登录用户认证信息
     * @param conversationId 会话 ID
     * @return 无数据成功响应
     */
    @DeleteMapping("/{conversationId}")
    public Result<Void> deleteConversation(Authentication authentication,
                                           @PathVariable Long conversationId) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        conversationService.deleteConversation(userId, conversationId);
        return Result.success();
    }

    /**
     * 会话内多轮对话。
     *
     * <p>入参 {@link ConversationChatRequest} 的 message 校验（{@code @NotBlank}
     * + {@code @Size(max=2000)}）；会话 ID 走路径参数。返回 AI 回答 + token 统计。</p>
     *
     * @param authentication  当前登录用户认证信息
     * @param conversationId 会话 ID
     * @param request         用户消息
     * @return AI 回答 + token 统计
     */
    @PostMapping("/{conversationId}/messages")
    public Result<ConversationChatVO> chat(Authentication authentication,
                                           @PathVariable Long conversationId,
                                           @Valid @RequestBody ConversationChatRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        ConversationChatVO vo = conversationChatService.chat(userId, conversationId, request.getMessage());
        return Result.success(vo);
    }

    /**
     * 会话内 RAG 对话（同步）。
     *
     * <p>流程：检索知识库 Top K 块 → 阈值过滤 → 拼装 Prompt（历史 + 检索块 + 问题）
     * → 调 LLM → 回答与引用落库。返回结构与 Phase 11 无会话 RAG 一致
     * （{@link RagChatVO}），前端引用卡片渲染逻辑可直接复用。</p>
     *
     * <p><strong>会话内 RAG 与普通会话对话的关系</strong>：
     * 会话内 RAG 是「多轮对话 + RAG 检索」的融合——既有历史上下文（多轮连贯性），
     * 又有知识库检索块（事实依据），回答与引用全部落库，历史会话重开仍可见引用。
     * 前端在会话内调用本接口而非普通 /messages 接口，即可获得「基于知识库回答」的能力。</p>
     *
     * @param authentication  当前登录用户认证信息
     * @param conversationId 会话 ID
     * @param request         RAG 请求（question + topK + scoreThreshold）
     * @return AI 回答（含引用标注）+ 引用来源数组
     */
    @PostMapping("/{conversationId}/messages/rag")
    public Result<RagChatVO> ragChat(Authentication authentication,
                                     @PathVariable Long conversationId,
                                     @Valid @RequestBody ConversationRagRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        // 应用默认值：topK 默认 5，scoreThreshold 默认 0.3
        request.resolve();
        RagChatVO vo = conversationChatService.ragChat(
                userId, conversationId, request.getQuestion(),
                request.getTopK(), request.getScoreThreshold());
        return Result.success(vo);
    }

    /**
     * 会话内 RAG 对话（流式）。
     *
     * <p>流程与同步版一致，区别是回答采用 SSE 流式推送（打字机效果）：
     * 先发 citations 命名事件（引用来源 JSON），再逐块发送回答分片（data 事件），
     * 流结束后完整回答落库为 AI 消息。</p>
     *
     * <p><strong>为什么 produces 声明 text/event-stream</strong>：SSE 的标准 Content-Type，
     * 浏览器 EventSource API 依赖这个 MIME 类型建立持久连接。声明后 Spring 自动设置响应头
     * {@code Content-Type: text/event-stream}，客户端能正确识别为 SSE 流。</p>
     *
     * <p><strong>流式事件结构</strong>：
     * 1. citations 命名事件（event: citations, data: JSON 数组）——引用来源，前端先渲染引用列表；
     * 2. 若干 data 事件（默认事件，data: 分片文本）——回答逐块推送，前端逐字显示；
     * 3. 连接关闭（emitter.complete()）——流结束。</p>
     *
     * @param authentication  当前登录用户认证信息
     * @param conversationId 会话 ID
     * @param request         RAG 请求（question + topK + scoreThreshold）
     * @return SSE 发送器（由 Spring 自动管理，Controller 直接返回）
     */
    @PostMapping(value = "/{conversationId}/messages/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ragChatStream(Authentication authentication,
                                    @PathVariable Long conversationId,
                                    @Valid @RequestBody ConversationRagRequest request) {
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        request.resolve();

        // 【为什么 timeout 设为 5 分钟？】RAG 流式响应的耗时结构是
        // 「检索毫秒级 + LLM 生成秒级」：检索 Top K 块通常在几百毫秒内完成，
        // LLM 流式生成（回答几百字）约 1~2 秒。极端场景（复杂问题 + 长回答）
        // 可能达数十秒，5 分钟是足够宽松的超时值。超时后 Spring 自动关闭连接，
        // 客户端收到 onerror 事件。
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        // 异步调用 Service（Spring AI 的 stream() 本身是异步的，subscribe 内部逻辑在 Reactor 线程池执行）
        conversationChatService.ragChatStream(
                userId, conversationId, request.getQuestion(),
                request.getTopK(), request.getScoreThreshold(), emitter);

        return emitter;
    }
}
