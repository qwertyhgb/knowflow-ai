package io.github.qwertyhgb.knowflow.ai.service;

import io.github.qwertyhgb.knowflow.ai.vo.RagChatVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话服务接口。
 *
 * <p>职责：把用户问题经过「检索增强」后交给大模型回答——先语义检索相关文档块，
 * 拼进 Prompt 作为参考资料，再让模型基于资料作答并标注引用。</p>
 */
public interface RagChatService {

    /**
     * RAG 对话（同步）。
     *
     * <p>完整链路：<strong>User Question → 语义检索 → 阈值过滤 → 拼装上下文 →
     * LLM 回答 → Answer + Citation</strong>。若过滤后没有可用的相关块，
     * 直接返回友好提示而不调用 LLM（没有资料硬答只会编造）。</p>
     *
     * @param userId         当前登录用户 ID：RAG 会调用付费 LLM（token 计费），需按用户限流
     * @param question       用户问题
     * @param topK           召回候选数（1~20，调用方已校验）
     * @param scoreThreshold 相似度阈值（0.0~1.0，低于此分数的块丢弃）
     * @return 模型回答 + 引用来源数组
     */
    RagChatVO chat(Long userId, String question, int topK, double scoreThreshold);

    /**
     * RAG 对话（流式 SSE）。
     *
     * <p>与 {@link #chat} 共享同一套「检索 + 阈值过滤 + 上下文拼装」逻辑，但回答改为
     * 逐块推送：先发一个 citations 命名事件（引用来源 JSON），再以默认 data 事件
     * 流式推送回答分片，完成时结束连接。</p>
     *
     * <p>【为什么用 {@link SseEmitter} 而不是返回 Flux？】
     * 与 AiChatServiceImpl 同一决策：项目是 Spring MVC（spring-boot-starter-webmvc），
     * SseEmitter 是 MVC 原生 SSE 机制，无需引入 WebFlux 全家桶；Flux 是 Spring AI
     * 内部返回的响应式流，我们在 Service 里消费它并手动转发到 SseEmitter。</p>
     *
     * @param userId         当前登录用户 ID：流式同样消耗 token，必须与同步一起限流
     * @param question       用户问题
     * @param topK           召回候选数（1~20，调用方已校验）
     * @param scoreThreshold 相似度阈值（0.0~1.0，低于此分数的块丢弃）
     * @param emitter        由 Controller 创建并返回给框架的 SSE 发射器
     */
    void chatStream(Long userId, String question, int topK, double scoreThreshold, SseEmitter emitter);
}
