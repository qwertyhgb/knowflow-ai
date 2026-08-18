package io.github.qwertyhgb.knowflow.ai.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 对话服务接口。
 *
 * <p>遵循项目 Controller → Service 的主线分层。接口保留是为了语义清晰
 * （AI 能力聚合点），实现类见 {@code impl.AiChatServiceImpl}。</p>
 */
public interface AiChatService {

    /**
     * 发送单轮对话，调用大模型并返回回答文本。
     *
     * @param message 用户消息（已由 DTO 校验非空且长度 ≤2000）
     * @return 模型生成的回答文本
     */
    String chat(String message);

    /**
     * 流式对话：把模型逐块生成的回答通过 SSE 推送给客户端，实现「打字机」效果。
     *
     * <p>【为什么用 {@link SseEmitter} 而不是返回 Flux？】
     * 本项目是 Spring MVC（spring-boot-starter-webmvc），不是 WebFlux。
     * {@link SseEmitter} 是 MVC 原生的 SSE 机制（把响应写成 text/event-stream），
     * 无需引入 WebFlux 全家桶；而 Flux 是 Spring AI 内部返回的响应式流，
     * 我们在 Service 里「消费」Flux，再把每个分片手动转发到 SseEmitter——
     * 「内部用 Flux 承载流、对外用 SseEmitter 承载 SSE」是两个不冲突的概念。</p>
     *
     * @param message 用户消息（已由 DTO 校验非空且长度 ≤2000）
     * @param emitter 由 Controller 创建并返回给框架的 SSE 发射器，本方法向其推送分片
     */
    void chatStream(String message, SseEmitter emitter);
}
