package io.github.qwertyhgb.knowflow.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.qwertyhgb.knowflow.ai.entity.AiMessage;
import io.github.qwertyhgb.knowflow.ai.entity.Conversation;
import io.github.qwertyhgb.knowflow.ai.enums.AiMessageRole;
import io.github.qwertyhgb.knowflow.ai.mapper.AiMessageMapper;
import io.github.qwertyhgb.knowflow.ai.mapper.ConversationMapper;
import io.github.qwertyhgb.knowflow.ai.service.ConversationChatService;
import io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService;
import io.github.qwertyhgb.knowflow.ai.vo.ConversationChatVO;
import io.github.qwertyhgb.knowflow.ai.vo.RagChatVO;
import io.github.qwertyhgb.knowflow.ai.vo.RagCitationVO;
import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 会话内多轮对话服务实现。
 *
 * <p>核心链路：<strong>校验归属 → 保存用户消息 → 标题自动生成 → 加载历史（滑动窗口）
 * → 构造 Prompt 调 LLM → 保存 AI 消息（含 token 统计）→ 更新会话时间</strong>。</p>
 *
 * <p><strong>上下文管理（教学文档讨论点）：聊天记录是否应该全部塞进 Prompt？</strong>
 * <strong>不能。</strong>三个理由：</p>
 * <ol>
 *   <li><strong>上下文窗口有 token 上限</strong>——大模型一次能接收的 token 有限，
 *       几十轮长对话的历史全部塞入会直接超限；</li>
 *   <li><strong>久远消息是噪声</strong>——几十轮前的消息与当前话题往往无关，塞进去
 *       会稀释模型的注意力、甚至引入过期信息误导回答；</li>
 *   <li><strong>每 token 都是成本</strong>——历史越长，每次请求的输入 token 越多，
 *       费用与延迟线性增长。</li>
 * </ol>
 * <p><strong>工程做法：滑动窗口取最近 N 条</strong>（本步 10 条）——只取最近 10 条消息
 * 作为上下文，最朴素也最有效。进阶策略（摘要压缩、按相关性裁剪）是后续优化主题。</p>
 *
 * <p><strong>为什么标题取首条消息前 20 字符？</strong>
 * 简单方案：首条用户消息往往概括了会话主题（「如何提升系统查询速度」之类），
 * 截断前 20 字符正好是会话列表里一眼能认出的短标题。用 LLM 生成一句话标题是
 * 更优雅但更昂贵的优化方向，本步先打通。</p>
 *
 * <p><strong>为什么 token 缺失不阻塞？</strong>
 * Token 统计是观察性数据（成本观察用），不同模型/API 返回 usage 的能力不一，
 * 拿不到就存 null，不影响对话主流程。</p>
 *
 * <p><strong>为什么用户消息已落库但 AI 消息失败可接受？</strong>
 * LLM 调用失败时，用户消息已持久化、AI 消息未持久化。下次发送仍会带这条用户消息
 * 作为历史（缺失对应的 AI 回答），语义上可接受——提示用户重试即可。
 * 教学阶段不引入补偿机制（如事务回滚或重试队列）。</p>
 */
@Slf4j
@Service
public class ConversationChatServiceImpl implements ConversationChatService {

    /** 会话标题仍为默认值时，用首条用户消息前 N 字符自动生成标题。 */
    private static final int TITLE_MAX_LENGTH = 20;

    /** 历史上下文滑动窗口：最近 N 条消息拼入 Prompt。 */
    private static final int HISTORY_WINDOW_SIZE = 10;

    /** 默认会话标题（与 ai_conversation.title 列的 DEFAULT 一致）。 */
    private static final String DEFAULT_TITLE = "新对话";

    private static final String SYSTEM_PROMPT = """
            你是 KnowFlow 的智能助手，回答用户问题，保持对话连贯。以下是历史对话：
            """;

    /**
     * 会话内 RAG 的系统提示词（与 RagChatServiceImpl 一致）。
     *
     * <p>限定模型只能依据参考资料作答 + 引用编号约定。本步直接复用 Phase 11 的提示词，
     * 保证会话内 RAG 与无会话 RAG 的引用机制完全一致——回答中的 [1][2] 对应检索块
     * 而非历史消息，引用编号是检索块在过滤后列表中的序号。</p>
     */
    private static final String RAG_SYSTEM_PROMPT = """
            你是 KnowFlow 的企业知识库助手。
            回答时只能依据下方「参考资料」中的内容，不得编造或使用资料之外的知识；
            资料不足时直接说明「资料中没有相关内容」；
            回答中引用资料处用 [序号] 标注，序号对应参考资料编号；
            用简洁专业的中文回答。
            """;

    /** 没有可用资料时的友好提示（与 RagChatServiceImpl 一致，不调用 LLM）。 */
    private static final String NO_CONTENT_REPLY =
            "抱歉，知识库中没有找到与您问题相关的内容，请换个问法或稍后再试";

    private final ConversationMapper conversationMapper;
    private final AiMessageMapper aiMessageMapper;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final SemanticSearchService semanticSearchService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public ConversationChatServiceImpl(ConversationMapper conversationMapper,
                                       AiMessageMapper aiMessageMapper,
                                       ObjectProvider<ChatClient> chatClientProvider,
                                       SemanticSearchService semanticSearchService,
                                       JsonMapper jsonMapper,
                                       Clock clock) {
        this.conversationMapper = conversationMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.chatClientProvider = chatClientProvider;
        this.semanticSearchService = semanticSearchService;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ConversationChatVO chat(Long userId, Long conversationId, String message) {
        // ---- 1. 校验会话归属（非本人/不存在统一 404）----
        Conversation conversation = requireOwnedConversation(userId, conversationId);

        // ---- 2. ChatClient 判空（未装配 → 503）----
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.warn("event=conversation_chat_failed reason=chat_client_unavailable conversationId={}",
                    conversationId);
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }

        Instant now = clock.instant();

        // ---- 3. 保存用户消息 ----
        // 用户消息先落库：即使后续 LLM 失败，这条输入也保留在会话历史里
        // （见类注释「AI 消息失败可接受」）。
        AiMessage userMessage = new AiMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole(AiMessageRole.USER);
        userMessage.setContent(message);
        userMessage.setCreatedAt(now);
        aiMessageMapper.insert(userMessage);

        // ---- 4. 标题自动生成 ----
        // 若会话标题仍是默认值，说明这是第一条消息，用其前 20 字符生成标题。
        // 注意：`"新对话".equals(title)` 而非 `title == null`——列有 DEFAULT 非空约束。
        ensureTitleGenerated(conversation, message);

        // ---- 5. 加载历史（滑动窗口）----
        // 查询该会话的全部消息，内存中取最近 10 条。
        // 【为什么用「查全量 + 截取」而不是 SQL LIMIT？】教学阶段消息量小（单会话几十条），
        // 查全量按时间排序后取尾部最简单直观；消息量大时再改为 SQL 分页/倒序 LIMIT。
        // 滑动窗口：只取最近 N 条，即「升序列表的最后 N 条」。
        List<AiMessage> allMessages = aiMessageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, conversationId)
                        .orderByAsc(AiMessage::getCreatedAt));
        int fromIndex = Math.max(0, allMessages.size() - HISTORY_WINDOW_SIZE);
        List<AiMessage> history = allMessages.subList(fromIndex, allMessages.size());

        // ---- 6. 构造 Prompt ----
        // 历史是上下文，当前问题是本轮输入，分开拼。
        // 历史格式：「用户:xxx\n助手:xxx」，每轮一条，让模型看到完整的问答对。
        StringBuilder historyBuilder = new StringBuilder();
        for (AiMessage h : history) {
            String prefix = h.getRole() == AiMessageRole.USER ? "用户:" : "助手:";
            historyBuilder.append(prefix).append(h.getContent()).append('\n');
        }
        String promptText = historyBuilder + "用户:" + message;

        // ---- 7. 调用 LLM（同步）----
        // 优先用 call().chatResponse() 拿到 ChatResponse：既能取回答文本，
        // 又能从 metadata.usage 取 token 统计（输入/输出 token）。
        String reply;
        Integer inputTokens = null;
        Integer outputTokens = null;
        try {
            var chatResponse = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(promptText)
                    .call()
                    .chatResponse();
            reply = chatResponse.getResult().getOutput().getText();
            var usage = chatResponse.getMetadata().getUsage();
            // 【为什么对 token 值逐个判 null？】「usage 不存在」有两种形态：
            // 1. getUsage() 返回 null（某些实现）；
            // 2. getUsage() 非 null 但 token 值为 null（另一些实现用空 Usage 对象兜底）。
            // 两种都应按「拿不到就存 null」处理，不阻塞主流程。
            if (usage != null && usage.getPromptTokens() != null) {
                inputTokens = usage.getPromptTokens();
            }
            if (usage != null && usage.getCompletionTokens() != null) {
                outputTokens = usage.getCompletionTokens();
            }
        } catch (RuntimeException e) {
            // LLM 是外部依赖：失败记录安全日志（不含消息原文），转 503。
            // 用户消息已落库、AI 消息未落库——可接受（见类注释）。
            log.warn("event=conversation_chat_failed reason={} conversationId={}",
                    e.getClass().getSimpleName(), conversationId);
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, e.getMessage());
        }

        // ---- 8. 保存 AI 消息（含 token 统计）----
        AiMessage aiMessage = new AiMessage();
        aiMessage.setConversationId(conversationId);
        aiMessage.setRole(AiMessageRole.ASSISTANT);
        aiMessage.setContent(reply);
        aiMessage.setInputTokens(inputTokens);
        aiMessage.setOutputTokens(outputTokens);
        aiMessage.setCreatedAt(clock.instant());
        aiMessageMapper.insert(aiMessage);

        // ---- 9. 更新会话时间 ----
        // 每次对话都刷新 updated_at：会话列表按最近活跃倒序（见 ConversationServiceImpl）。
        conversation.setUpdatedAt(clock.instant());
        conversationMapper.updateById(conversation);

        log.info("event=conversation_chat conversationId={} userId={} historyCount={}",
                conversationId, userId, history.size());
        return ConversationChatVO.of(reply, inputTokens, outputTokens);
    }

    @Override
    @Transactional
    public RagChatVO ragChat(Long userId, Long conversationId, String question, int topK, double scoreThreshold) {
        // ---- 1. 校验会话归属（非本人/不存在统一 404）----
        Conversation conversation = requireOwnedConversation(userId, conversationId);

        // ---- 2. ChatClient 判空（未装配 → 503）----
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.warn("event=conversation_rag_chat_failed reason=chat_client_unavailable conversationId={}",
                    conversationId);
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }

        Instant now = clock.instant();

        // ---- 3. 保存用户消息（与 chat() 一致，先落库）----
        AiMessage userMessage = new AiMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole(AiMessageRole.USER);
        userMessage.setContent(question);
        userMessage.setCreatedAt(now);
        aiMessageMapper.insert(userMessage);

        // ---- 4. 标题自动生成（与 chat() 一致，抽取共享方法）----
        ensureTitleGenerated(conversation, question);

        // ---- 5. 检索 + 阈值过滤（复用 SemanticSearchService，与 RagChatServiceImpl 同逻辑）----
        // 【为什么检索逻辑在服务内实现而不是抽出？】本步按最小改动原则，
        // 直接调用已有的 SemanticSearchService.search + 阈值过滤，与 RagChatServiceImpl
        // 的 retrieveAndFilter 逻辑一致；后续两处检索逻辑若频繁调优，再抽共享检索组件。
        List<SemanticSearchVO> candidates = semanticSearchService.search(question, topK);
        List<SemanticSearchVO> filtered = candidates.stream()
                .filter(c -> c.getScore() == null || c.getScore() >= scoreThreshold)
                .toList();

        // ---- 6. 无相关块处理：不调 LLM，直接落库提示文本并返回 ----
        // 【为什么空上下文也要落库？】会话的完整性要求——用户问了、助手答了，
        // 即使答的是「资料不足」提示，也应该落库，重开会话时能看到这一轮对话。
        if (filtered.isEmpty()) {
            AiMessage aiMessage = new AiMessage();
            aiMessage.setConversationId(conversationId);
            aiMessage.setRole(AiMessageRole.ASSISTANT);
            aiMessage.setContent(NO_CONTENT_REPLY);
            aiMessage.setCitations("[]"); // 空数组 JSON
            aiMessage.setCreatedAt(clock.instant());
            aiMessageMapper.insert(aiMessage);

            conversation.setUpdatedAt(clock.instant());
            conversationMapper.updateById(conversation);

            log.info("event=conversation_rag_chat noContent=true conversationId={} userId={}", conversationId, userId);
            return RagChatVO.of(NO_CONTENT_REPLY, List.of());
        }

        // ---- 7. 加载历史（滑动窗口，与 chat() 一致）----
        List<AiMessage> allMessages = aiMessageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, conversationId)
                        .orderByAsc(AiMessage::getCreatedAt));
        int fromIndex = Math.max(0, allMessages.size() - HISTORY_WINDOW_SIZE);
        List<AiMessage> history = allMessages.subList(fromIndex, allMessages.size());

        // ---- 8. 构造 Prompt：历史 + 检索块 + 问题三段式 ----
        // 【为什么三段式拼接？】
        //   - 历史对话（history）：提供对话连贯性，让模型理解上下文，支持跨轮引用
        //     （如「我上一个问题问的是什么」）；
        //   - 参考资料（filtered blocks）：提供事实依据，让模型基于真实数据作答，
        //     而不是闭卷发挥；编号为 [1][2]，与 citations 数组一一对应；
        //   - 用户问题（question）：驱动本轮回答，明确本轮意图。
        // 三段式结构：历史在前（上下文铺垫）、资料居中（事实依据）、问题最后（当前意图）。
        StringBuilder historyBuilder = new StringBuilder();
        for (AiMessage h : history) {
            String prefix = h.getRole() == AiMessageRole.USER ? "用户:" : "助手:";
            historyBuilder.append(prefix).append(h.getContent()).append('\n');
        }

        String contextBlocks = buildContext(filtered);
        String userPrompt = "历史对话:\n%s\n\n参考资料:\n%s\n\n用户问题:%s"
                .formatted(historyBuilder.toString(), contextBlocks, question);

        // ---- 9. 调用 LLM（同步，与 RagChatServiceImpl 一致）----
        String reply;
        Integer inputTokens = null;
        Integer outputTokens = null;
        try {
            var chatResponse = chatClient.prompt()
                    .system(RAG_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .chatResponse();
            reply = chatResponse.getResult().getOutput().getText();
            var usage = chatResponse.getMetadata().getUsage();
            if (usage != null && usage.getPromptTokens() != null) {
                inputTokens = usage.getPromptTokens();
            }
            if (usage != null && usage.getCompletionTokens() != null) {
                outputTokens = usage.getCompletionTokens();
            }
        } catch (RuntimeException e) {
            log.warn("event=conversation_rag_chat_failed reason={} conversationId={}",
                    e.getClass().getSimpleName(), conversationId);
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, e.getMessage());
        }

        // ---- 10. 保存 AI 消息（回答 + 引用 JSON + token）----
        // 引用 JSON 序列化失败不阻塞：引用是增强信息，回答本身更重要。
        // 【为什么引用序列化失败不阻塞？】引用是给前端展示「查看原文」的元数据，
        // 序列化失败（如 Jackson 配置问题、循环引用等）时，存空数组不应该丢弃回答——
        // 回答是用户真正需要的，引用缺失可降级为「无引用模式」，不影响业务主流程。
        List<RagCitationVO> citations = toCitations(filtered);
        String citationsJson;
        try {
            citationsJson = jsonMapper.writeValueAsString(citations);
        } catch (JacksonException e) {
            log.warn("event=citations_serialization_failed conversationId={} reason={}",
                    conversationId, e.getClass().getSimpleName());
            citationsJson = "[]"; // 序列化失败，存空数组
        }

        AiMessage aiMessage = new AiMessage();
        aiMessage.setConversationId(conversationId);
        aiMessage.setRole(AiMessageRole.ASSISTANT);
        aiMessage.setContent(reply);
        aiMessage.setCitations(citationsJson);
        aiMessage.setInputTokens(inputTokens);
        aiMessage.setOutputTokens(outputTokens);
        aiMessage.setCreatedAt(clock.instant());
        aiMessageMapper.insert(aiMessage);

        // ---- 11. 更新会话时间 ----
        conversation.setUpdatedAt(clock.instant());
        conversationMapper.updateById(conversation);

        log.info("event=conversation_rag_chat conversationId={} userId={} usedCount={} historyCount={}",
                conversationId, userId, filtered.size(), history.size());
        return RagChatVO.of(reply, citations);
    }

    @Override
    @Transactional
    public void ragChatStream(Long userId, Long conversationId, String question, int topK, double scoreThreshold, SseEmitter emitter) {
        // ---- 1. 校验会话归属 ----
        Conversation conversation;
        try {
            conversation = requireOwnedConversation(userId, conversationId);
        } catch (BusinessException e) {
            // 归属校验失败：SSE 连接已建立，只能发 error 事件（而不是抛 404）
            sendErrorEvent(emitter, e.getMessage());
            return;
        }

        // ---- 2. ChatClient 判空 ----
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.warn("event=conversation_rag_chat_stream_failed reason=chat_client_unavailable conversationId={}",
                    conversationId);
            sendErrorEvent(emitter, ErrorCode.AI_SERVICE_UNAVAILABLE.getMessage());
            return;
        }

        Instant now = clock.instant();

        // ---- 3. 保存用户消息（需要立即提交，避免流式过程中回滚导致用户消息丢失）----
        AiMessage userMsg = new AiMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole(AiMessageRole.USER);
        userMsg.setContent(question);
        userMsg.setCreatedAt(now);
        aiMessageMapper.insert(userMsg);

        // ---- 4. 标题自动生成 ----
        ensureTitleGenerated(conversation, question);

        // ---- 5. 检索 + 阈值过滤（与同步版一致）----
        List<SemanticSearchVO> candidates = semanticSearchService.search(question, topK);
        List<SemanticSearchVO> filtered = candidates.stream()
                .filter(c -> c.getScore() == null || c.getScore() >= scoreThreshold)
                .toList();

        // ---- 6. 无相关块处理：发提示文本并结束，同时落库 ----
        // 【为什么流式场景也要落库？】与同步版一致（保证历史完整），
        // 用户消息已落库、助手提示也应落库，重开会话能看到这一轮对话。
        if (filtered.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().data(NO_CONTENT_REPLY));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }

            AiMessage aiMessage = new AiMessage();
            aiMessage.setConversationId(conversationId);
            aiMessage.setRole(AiMessageRole.ASSISTANT);
            aiMessage.setContent(NO_CONTENT_REPLY);
            aiMessage.setCitations("[]");
            aiMessage.setCreatedAt(clock.instant());
            aiMessageMapper.insert(aiMessage);

            conversation.setUpdatedAt(clock.instant());
            conversationMapper.updateById(conversation);

            log.info("event=conversation_rag_chat_stream noContent=true conversationId={} userId={}",
                    conversationId, userId);
            return;
        }

        // ---- 7. 加载历史（与同步版一致）----
        List<AiMessage> allMessages = aiMessageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, conversationId)
                        .orderByAsc(AiMessage::getCreatedAt));
        int fromIndex = Math.max(0, allMessages.size() - HISTORY_WINDOW_SIZE);
        List<AiMessage> history = allMessages.subList(fromIndex, allMessages.size());

        // ---- 8. 先发 citations 命名事件 ----
        // 【为什么 citations 先于回答发出？】前端需要先知道引用来源，回答中的 [1][2]
        // 才能即时对应上——否则回答已经流式显示了，引用列表还没到。
        List<RagCitationVO> citations = toCitations(filtered);
        try {
            String citationsJson = jsonMapper.writeValueAsString(citations);
            emitter.send(SseEmitter.event().name("citations").data(citationsJson));
        } catch (IOException e) {
            // citations 发送失败（客户端已断开）
            emitter.completeWithError(e);
            return;
        }

        // ---- 9. 拼装 Prompt 并流式调用 LLM ----
        StringBuilder historyBuilder = new StringBuilder();
        for (AiMessage h : history) {
            String prefix = h.getRole() == AiMessageRole.USER ? "用户:" : "助手:";
            historyBuilder.append(prefix).append(h.getContent()).append('\n');
        }

        String contextBlocks = buildContext(filtered);
        String userMessagePrompt = "历史对话:\n%s\n\n参考资料:\n%s\n\n用户问题:%s"
                .formatted(historyBuilder.toString(), contextBlocks, question);

        // 【为什么用 StringBuilder 累加回答？】流式响应是逐块到达的（打字机效果），
        // 每个分片立即推送给客户端，但数据库里需要存完整回答——必须在内存中累加，
        // onComplete 时统一落库。累加逻辑：append 每个分片，最后 toString() 是完整回答。
        StringBuilder replyBuilder = new StringBuilder();

        chatClient.prompt()
                .system(RAG_SYSTEM_PROMPT)
                .user(userMessagePrompt)
                .stream()
                .content()
                .subscribe(
                        // onNext：每到一个分片就推送给客户端（打字机效果），同时累加到 replyBuilder
                        chunk -> {
                            replyBuilder.append(chunk);
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        // onError：流中途失败，发 error 事件（不落库 AI 消息，用户消息已落库可接受）
                        error -> {
                            log.warn("event=conversation_rag_chat_stream_failed reason={} conversationId={}",
                                    error.getClass().getSimpleName(), conversationId);
                            sendErrorEvent(emitter, ErrorCode.AI_SERVICE_UNAVAILABLE.getMessage());
                        },
                        // onComplete：流正常结束，关闭 SSE 连接，把完整回答落库
                        () -> {
                            emitter.complete();

                            // 【为什么流式结束才落库？】流式响应是异步的，每个分片到达的时间不确定，
                            // 无法在流中途做数据库写入（并发控制复杂且性能差）；只有在流正常结束时，
                            // 才能拿到完整回答（replyBuilder.toString()），一次性落库。
                            // 【为什么 token 存 null？】流式 API 默认不返回 usage 信息，
                            // 拿 token 统计需要额外调用（如 OpenAI 的 stream_options: {"include_usage": true}），
                            // 教学阶段简化，token 统计仅同步接口提供，流式版存 null 可接受。
                            String fullReply = replyBuilder.toString();
                            String citationsJson;
                            try {
                                citationsJson = jsonMapper.writeValueAsString(citations);
                            } catch (JacksonException e) {
                                log.warn("event=citations_serialization_failed conversationId={} reason={}",
                                        conversationId, e.getClass().getSimpleName());
                                citationsJson = "[]";
                            }

                            AiMessage aiMessage = new AiMessage();
                            aiMessage.setConversationId(conversationId);
                            aiMessage.setRole(AiMessageRole.ASSISTANT);
                            aiMessage.setContent(fullReply);
                            aiMessage.setCitations(citationsJson);
                            aiMessage.setInputTokens(null); // 流式不统计 token
                            aiMessage.setOutputTokens(null);
                            aiMessage.setCreatedAt(clock.instant());
                            aiMessageMapper.insert(aiMessage);

                            conversation.setUpdatedAt(clock.instant());
                            conversationMapper.updateById(conversation);

                            log.info("event=conversation_rag_chat_stream conversationId={} userId={} usedCount={} historyCount={}",
                                    conversationId, userId, filtered.size(), history.size());
                        }
                );
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 确保会话标题已生成：若标题仍为默认值，用首条消息前 20 字符生成标题。
     *
     * <p>抽取自 chat() 方法，与 ragChat/ragChatStream 共享同一逻辑——
     * 所有对话接口都应在第一条消息时自动生成标题，避免重复实现。</p>
     */
    private void ensureTitleGenerated(Conversation conversation, String message) {
        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            String title = message.length() <= TITLE_MAX_LENGTH
                    ? message
                    : message.substring(0, TITLE_MAX_LENGTH);
            conversation.setTitle(title);
            conversationMapper.updateById(conversation);
        }
    }

    /**
     * 把过滤后的块拼装为带编号的上下文（与 RagChatServiceImpl 同逻辑）。
     *
     * <p>按顺序编号 [1][2][3]...，每块附带来源文件名与块序号，模型能据此回答
     * 「出自哪个文档」；编号与回答中的 [序号] 一一对应（Citation 机制基础）。</p>
     */
    private String buildContext(List<SemanticSearchVO> blocks) {
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            SemanticSearchVO block = blocks.get(i);
            if (i > 0) {
                contextBuilder.append('\n');
            }
            contextBuilder.append("[%d] 来源:%s 第%d块\n%s"
                    .formatted(i + 1, block.getFileName(), block.getChunkIndex(), block.getChunkText()));
        }
        return contextBuilder.toString();
    }

    /**
     * 把过滤后的块映射为引用 VO 列表（与 RagChatServiceImpl 同逻辑）。
     *
     * <p>字段与 {@link SemanticSearchVO} 一一对应直接透传，与回答中的 [序号] 顺序一致。</p>
     */
    private List<RagCitationVO> toCitations(List<SemanticSearchVO> blocks) {
        return blocks.stream()
                .map(c -> RagCitationVO.of(c.getDocumentId(), c.getKnowledgeBaseId(),
                        c.getFileName(), c.getChunkIndex(), c.getChunkText(), c.getScore()))
                .toList();
    }

    /**
     * 向 SSE 客户端发送 error 事件并结束连接（与 RagChatServiceImpl 同逻辑）。
     *
     * <p>【为什么错误走事件而不是异常？】SSE 连接一旦建立，HTTP 状态码已经确定（200），
     * 无法中途改成 503/404；因此失败只能以 error 命名事件推送给客户端。</p>
     */
    private void sendErrorEvent(SseEmitter emitter, String errorMessage) {
        try {
            emitter.send(SseEmitter.event().name("error").data(errorMessage));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * 校验会话归属并返回会话实体；非本人或不存在统一抛 404。
     *
     * <p>与 {@link ConversationServiceImpl} 的私有方法同语义：不区分「不存在」与
     * 「无权访问」，避免泄露他人会话存在性。</p>
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
