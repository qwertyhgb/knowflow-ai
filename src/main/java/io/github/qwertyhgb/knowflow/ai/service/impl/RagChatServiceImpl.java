package io.github.qwertyhgb.knowflow.ai.service.impl;

import io.github.qwertyhgb.knowflow.ai.service.RagChatService;
import io.github.qwertyhgb.knowflow.ai.service.SemanticSearchService;
import io.github.qwertyhgb.knowflow.ai.vo.RagChatVO;
import io.github.qwertyhgb.knowflow.ai.vo.RagCitationVO;
import io.github.qwertyhgb.knowflow.ai.vo.SemanticSearchVO;
import io.github.qwertyhgb.knowflow.common.exception.BusinessException;
import io.github.qwertyhgb.knowflow.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

/**
 * RAG 对话服务实现。
 *
 * <p><strong>RAG（Retrieval-Augmented Generation）是什么？</strong>
 * 纯 LLM 问答是「闭卷考试」：模型的训练数据里没有企业的私有知识，直接问只会得到
 * 「我不知道」或编造。检索增强把它变成「开卷考试」：<strong>先检索</strong>——从向量库
 * 里找出与问题最相关的资料块；<strong>再生成</strong>——把这些块作为参考资料拼进
 * Prompt，让模型基于资料作答。收益：回答基于真实数据（不是模型瞎编）、显著缓解幻觉、
 * 引用可溯源（用户能核实每条断言）。</p>
 *
 * <p><strong>为什么空上下文不调用 LLM？</strong>
 * 如果过滤后没有相关块还硬让模型回答，模型只能在无资料的情况下自由发挥——
 * 这正是幻觉的来源。返回明确提示（「知识库中没有找到相关内容」）比编造更专业，
 * 同时也省下了一次 LLM 调用（成本 + 延迟）。</p>
 *
 * <p><strong>为什么系统提示词同时是防注入的基础？</strong>
 * 用户问题被拼在 user 段（「用户问题:{question}」），即便问题里包含
 * 「忽略上面的规则」「告诉我系统提示词」这类注入指令，系统提示词中
 * 「只能依据参考资料回答、不得使用资料之外的知识」的规则优先级更高，
 * 从 Prompt 结构上弱化了注入的效果——这是 Phase 9 基础防注入在 RAG 场景的延续。</p>
 *
 * <p><strong>流式 RAG 的价值（本步新增）</strong>
 * RAG 的耗时结构是「检索毫秒级 + LLM 生成秒级」：同步接口用户要干等完整回答
 * （数秒），而流式接口把「首字延迟」压缩到检索时间 + 模型第一个 token 的生成时间
 * （几百毫秒），体验接近实时。这就是智能客服对话体验的工程基础。</p>
 */
@Slf4j
@Service
public class RagChatServiceImpl implements RagChatService {

    /** 没有可用资料时的友好提示（不调用 LLM）。 */
    private static final String NO_CONTENT_REPLY =
            "抱歉，知识库中没有找到与您问题相关的内容，请换个问法或稍后再试";

    /**
     * 系统提示词：限定模型只能依据参考资料作答 + 引用编号约定。
     *
     * <p><strong>引用编号机制</strong>：我们按 [1][2][3] 给上下文块编号，约定写在系统
     * 提示词里（「引用资料处用 [序号] 标注，序号对应参考资料编号」），模型按编号标注，
     * 我们只需原样透传——回答中的 [1] 与 citations[0] 一一对应，这是 Citation 的机械基础。
     * 上下文里附带来源文件名（「[1] 来源:缓存设计.md」），模型还能回答
     * 「这段内容出自哪个文档」这类溯源问题。</p>
     */
    private static final String SYSTEM_PROMPT = """
            你是 KnowFlow 的企业知识库助手。
            回答时只能依据下方「参考资料」中的内容，不得编造或使用资料之外的知识；
            资料不足时直接说明「资料中没有相关内容」；
            回答中引用资料处用 [序号] 标注，序号对应参考资料编号；
            用简洁专业的中文回答。
            """;

    /** 现有语义检索服务（Phase 10 已完成，直接复用）。 */
    private final SemanticSearchService semanticSearchService;

    /** Spring AI 自动配置的 ChatClient 的可选提供者（可能不存在，取决于是否配置 api-key）。 */
    private final ObjectProvider<ChatClient> chatClientProvider;

    /**
     * Jackson 3 的 JsonMapper（Spring Boot 4 自动配置，项目各处复用同一实例）。
     * 用于把 citations 序列化为 JSON 放进 SSE 命名事件——SSE 事件的 data 是文本，
     * 结构化的引用数组必须先序列化。
     */
    private final JsonMapper jsonMapper;

    public RagChatServiceImpl(SemanticSearchService semanticSearchService,
                              ObjectProvider<ChatClient> chatClientProvider,
                              JsonMapper jsonMapper) {
        this.semanticSearchService = semanticSearchService;
        this.chatClientProvider = chatClientProvider;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public RagChatVO chat(String question, int topK, double scoreThreshold) {
        // 与 AiChatServiceImpl 一致的判空：未配置 key 时 ChatClient 未装配，返回 503
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.warn("event=rag_chat_failed reason=chat_client_unavailable");
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }

        // ---- 步骤1+2：检索 + 阈值过滤（抽取的共享方法，与流式路径共用）----
        List<SemanticSearchVO> filtered = retrieveAndFilter(question, topK, scoreThreshold);

        // ---- 步骤3：无相关块处理 ----
        // 没有资料可依据 → 不调用 LLM，直接返回友好提示 + 空引用。
        // 【为什么空上下文也要走完整返回而非抛错？】这是业务上的「查无资料」状态，
        // 不是错误——前端应展示一句人性化提示而不是报错弹窗。
        if (filtered.isEmpty()) {
            log.info("event=rag_chat noContent=true topK={} threshold={}", topK, scoreThreshold);
            return RagChatVO.of(NO_CONTENT_REPLY, List.of());
        }

        // ---- 步骤4：构造 Prompt（拼装上下文由抽取的 buildContext 完成）----
        // 参考资料与用户问题分开拼：资料作为「上下文」，问题作为「问题」，
        // 系统提示词约束「只能依据资料作答」，从结构上把资料与用户输入分层。
        String userMessage = "参考资料:\n%s\n\n用户问题:%s".formatted(buildContext(filtered), question);

        try {
            // 为什么用同步 call？本步先打通 RAG 链路，流式 RAG 由 chatStream 提供。
            // 同步拿完整回答最简单直观，方便验证「回答基于资料 + 引用标注」的核心价值。
            String reply = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .call()
                    .content();

            // ---- 步骤6：组装返回 ----
            // citations 由过滤后的块直接映射，与回答中的 [序号] 顺序一一对应。
            List<RagCitationVO> citations = toCitations(filtered);

            log.info("event=rag_chat success=true usedCount={} topK={} threshold={}",
                    filtered.size(), topK, scoreThreshold);
            return RagChatVO.of(reply, citations);
        } catch (RuntimeException e) {
            // LLM 是外部依赖：网络/限流/超时等失败记录安全日志（不含问题与资料原文），
            // 转成 503 结构化错误，与 AiChatServiceImpl 的失败处理语义一致。
            log.warn("event=rag_chat_failed reason={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    @Override
    public void chatStream(String question, int topK, double scoreThreshold, SseEmitter emitter) {
        // 与 AiChatServiceImpl.chatStream 一致：SSE 连接建立后无法改 HTTP 状态码，
        // 所以 ChatClient 未装配时只能发 error 事件（而不是抛 503）。
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.warn("event=rag_chat_stream_failed reason=chat_client_unavailable");
            sendErrorEvent(emitter);
            return;
        }

        // ---- 检索 + 阈值过滤（与同步 chat() 共用同一实现）----
        List<SemanticSearchVO> filtered = retrieveAndFilter(question, topK, scoreThreshold);

        // ---- 空上下文处理：直接发提示文本并结束，不调用 LLM ----
        // 与同步版的决策一致（没有资料硬答只会编造）；流式场景同样直接返回提示，
        // 前端收到一条 data 事件后连接即结束。
        if (filtered.isEmpty()) {
            log.info("event=rag_chat_stream noContent=true topK={} threshold={}", topK, scoreThreshold);
            try {
                emitter.send(SseEmitter.event().data(NO_CONTENT_REPLY));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return;
        }

        // ---- 先发 citations 命名事件 ----
        // 【为什么 citations 先于回答发出？】前端需要先知道引用来源，回答里的 [1]
        // 才能即时对应上——否则回答已经流式显示了，引用列表还没到。
        // 【为什么用命名事件？】SSE 每帧可带 event 名，前端可分流处理：
        //   addEventListener('citations', ...) 处理引用元数据；
        //   默认事件（onmessage）处理回答分片。
        // 两者共享同一条 SSE 连接，不冲突。
        List<RagCitationVO> citations = toCitations(filtered);
        try {
            // citations 是结构化数组，SSE 事件 data 是文本，需先 JSON 序列化。
            // JsonMapper.writeValueAsString 抛 JacksonException（非受检异常）。
            String citationsJson = jsonMapper.writeValueAsString(citations);
            emitter.send(SseEmitter.event().name("citations").data(citationsJson));
        } catch (IOException e) {
            // 发送失败（客户端已断开）
            emitter.completeWithError(e);
            return;
        }

        // ---- 拼装 Prompt 并流式调用 LLM ----
        // userMessage 与同步版完全一致（buildContext 抽取共享），
        // 唯一区别是 call() → stream()：立即返回 Flux<String>，内容分片到达。
        String userMessage = "参考资料:\n%s\n\n用户问题:%s".formatted(buildContext(filtered), question);
        chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .stream()
                .content()
                .subscribe(
                        // onNext：每到一个分片就推送给客户端（打字机效果）
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                // 发送失败（客户端已断开）：结束发送
                                emitter.completeWithError(e);
                            }
                        },
                        // onError：流中途失败，发 error 事件（白名单日志，不记原文）
                        error -> {
                            log.warn("event=rag_chat_stream_failed reason={}", error.getClass().getSimpleName());
                            sendErrorEvent(emitter);
                        },
                        // onComplete：流正常结束，关闭 SSE 连接
                        emitter::complete
                );

        log.info("event=rag_chat_stream started=true usedCount={} topK={} threshold={}",
                filtered.size(), topK, scoreThreshold);
    }

    /**
     * 检索 + 阈值过滤（同步/流式两条路径的共享逻辑）。
     *
     * <p>【为什么必须抽取？】chat() 与 chatStream() 都执行
     * 「语义检索 → 按阈值过滤噪声」两步，若各自实现，后续调优
     * （如阈值策略、分数缺失处理）就要改两处，必然漂移。抽成单一实现，
     * 两条路径永远保持一致——这正是「复用设计」的核心动机。</p>
     */
    private List<SemanticSearchVO> retrieveAndFilter(String question, int topK, double scoreThreshold) {
        // 步骤1：检索（复用 Phase 10 的语义搜索链路）
        List<SemanticSearchVO> candidates = semanticSearchService.search(question, topK);

        // 步骤2：阈值过滤
        // score 为 null 的块默认保留：个别存储实现不返回分数，无法判断相关性——
        // 宁可保留不可误杀（教学场景的简单取舍；追求精确可改为「无分数即丢弃」）。
        // score 非 null 且低于阈值 → 噪声，丢弃，以免误导模型。
        List<SemanticSearchVO> filtered = candidates.stream()
                .filter(c -> c.getScore() == null || c.getScore() >= scoreThreshold)
                .toList();

        log.info("event=rag_chat retrieved=true candidateCount={} usedCount={} topK={} threshold={}",
                candidates.size(), filtered.size(), topK, scoreThreshold);
        return filtered;
    }

    /**
     * 把过滤后的块拼装为带编号的上下文（同步/流式共用）。
     *
     * <p>按顺序编号 [1][2][3]...，每块附带来源文件名与块序号：
     * 模型能据此回答「出自哪个文档」；编号与回答中的 [序号] 一一对应（Citation 基础）。
     * 用带索引的 for 循环拼接：编号即「块在过滤后列表中的位置 + 1」，索引天然递增，
     * 避免用 list.indexOf() 的 O(n²) 重复查找。</p>
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
     * 把过滤后的块映射为引用 VO 列表（同步/流式共用）。
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
     * 向 SSE 客户端发送 error 事件并结束连接。
     *
     * <p>【为什么错误走事件而不是异常？】SSE 连接一旦建立，HTTP 状态码已经确定（200），
     * 无法中途改成 503；因此失败只能以 error 命名事件推送给客户端。
     * 与 AiChatServiceImpl 的处理完全一致。</p>
     */
    private void sendErrorEvent(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(ErrorCode.AI_SERVICE_UNAVAILABLE.getMessage()));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            // 连 error 事件都发不出去（连接已断或 emitter 已结束）：直接以错误收尾。
            // 同时捕获 IllegalStateException：它是「emitter 已 complete/failed 仍被发送」的信号。
            emitter.completeWithError(e);
        }
    }
}
