package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.dto.request.AiChatRequest;
import io.github.qwertyhgb.knowflow.ai.service.AiChatService;
import io.github.qwertyhgb.knowflow.ai.vo.AiChatResponse;
import io.github.qwertyhgb.knowflow.auth.context.EnterpriseUser;
import io.github.qwertyhgb.knowflow.common.response.Result;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 对话接口。
 *
 * <p>POST /api/ai/chat —— 接收用户消息，调用大模型，返回回答。</p>
 *
 * <p>【本接口会自动受 Security 保护吗？】
 * 项目的 Security 默认全局生效（除注册/登录/Swagger 外均需登录），
 * 因此本接口无需额外声明即可自动要求登录态——AI 能力不会向匿名用户开放。
 * Controller 只做「参数收获 + 调用 Service + 组装 VO」，与项目现有 Controller 风格一致。
 * 待企业知识库场景接入时（Phase 11 RAG），再叠加企业上下文与提示词增强。</p>
 */
@RestController
@RequestMapping("/api/ai/chat")
public class AiChatController {

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    /**
     * 单轮对话。
     *
     * @param aiChatRequest 用户消息（{@code @Valid} 触发 {@code @NotBlank}/{@code @Size} 校验）
     * @return {@code Result<AiChatResponse>}，reply 为模型回答
     */
    /**
     * 流式接口的超时时间（毫秒）：120 秒。
     *
     * <p>【为什么必须显式设长超时？】SSE 是长连接，大模型生成回答可能需要几十秒。
     * 若用默认超时（SseEmitter 无参构造依赖容器的 async 超时，通常很短），
     * 连接会在大模型还没生成完时就被提前断开。显式设 120 秒给生成留足时间。</p>
     */
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    @PostMapping
    public Result<AiChatResponse> chat(Authentication authentication,
                                       @Valid @RequestBody AiChatRequest aiChatRequest) {
        // 从认证主体取 userId 传给 Service：限流必须按用户维度（付费调用防刷），
        // 遵循项目既有的 EnterpriseUser principal 模式（与会话/工单控制器一致）。
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        String reply = aiChatService.chat(userId, aiChatRequest.getMessage());
        return Result.success(AiChatResponse.of(reply));
    }

    /**
     * 流式对话（SSE）。
     *
     * <p>【为什么 produces 要声明 {@code text/event-stream}？】
     * 响应头 Content-Type 必须是 text/event-stream，浏览器原生的 EventSource 才会
     * 识别并按 SSE 协议逐条消费；否则会被当成普通 JSON/文本处理。
     * Swagger 对 SSE 的展示能力有限，手动验证建议用 {@code curl -N}。</p>
     *
     * <p>【为什么这个接口不走 {@link Result} 统一响应包装？】
     * SSE 的响应体是一连串 {@code data: ...} 事件流，不是一个 JSON 对象，
     * 无法套进 {@code Result} 的 code/data/message 结构；流式接口天然例外。</p>
     *
     * <p>本接口与同步接口一样自动受 Security 保护（全局默认需登录），
     * 匿名请求会返回 401；入参校验失败（message 为空/超长）返回 400。</p>
     *
     * @param aiChatRequest 用户消息（{@code @Valid} 触发 {@code @NotBlank}/{@code @Size} 校验）
     * @return {@link SseEmitter}，由 Spring MVC 接管其生命周期并流式写出响应
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(Authentication authentication,
                                 @Valid @RequestBody AiChatRequest aiChatRequest) {
        // 在 Controller 层创建 SseEmitter 并返回，Service 只负责「往里面推分片」。
        // 超时显式设为 120 秒，避免长连接被默认短超时提前掐断。
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        aiChatService.chatStream(userId, aiChatRequest.getMessage(), emitter);
        return emitter;
    }
}
