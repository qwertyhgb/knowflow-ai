package io.github.qwertyhgb.knowflow.ai.controller;

import io.github.qwertyhgb.knowflow.ai.dto.request.RagChatRequest;
import io.github.qwertyhgb.knowflow.ai.service.RagChatService;
import io.github.qwertyhgb.knowflow.ai.vo.RagChatVO;
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
 * RAG 对话接口。
 *
 * <p>POST /api/ai/rag/chat —— 用户级接口（登录即可用，不依赖企业上下文），
 * 基于知识库语义检索的参考资料回答用户问题，返回回答 + 引用来源。</p>
 *
 * <p>【接口语义与权限边界】
 * 与 AI 对话/语义搜索接口一致：非企业作用域，登录即可用。知识库级权限过滤
 * （用户只能检索到自己可见的文档块，Phase 11 后续步骤结合企业上下文实现）
 * 是下一步主题——本步先打通 RAG 完整链路。</p>
 */
@RestController
@RequestMapping("/api/ai/rag")
public class RagChatController {

    /**
     * 流式接口的超时时间（毫秒）：120 秒。
     *
     * <p>【为什么必须显式设长超时？】SSE 是长连接，LLM 生成回答可能需要几十秒，
     * 默认短超时会提前掐断连接（与 AiChatController 的流式接口同构）。</p>
     */
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final RagChatService ragChatService;

    public RagChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    /**
     * RAG 对话（同步）。
     *
     * @param authentication 当前登录用户认证信息（提取 userId 传给 Service 做用户维限流）
     * @param request        问题 + 检索参数（{@code @Valid} 校验：question 非空且 ≤1000，
     *                       topK 1~20，scoreThreshold 0.0~1.0）
     * @return 回答 + 引用来源数组
     */
    @PostMapping("/chat")
    public Result<RagChatVO> chat(Authentication authentication,
                                  @Valid @RequestBody RagChatRequest request) {
        // 从认证主体取 userId 传给 Service：RAG 会调用付费 LLM，限流必须按用户维度防刷。
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        RagChatVO vo = ragChatService.chat(userId, request.getQuestion(),
                request.resolveTopK(), request.resolveScoreThreshold());
        return Result.success(vo);
    }

    /**
     * RAG 对话（流式 SSE）。
     *
     * <p>【为什么 produces 要声明 {@code text/event-stream}？】
     * 响应头 Content-Type 必须是 text/event-stream，浏览器原生的 EventSource 才会
     * 识别并按 SSE 协议逐条消费（与 AiChatController 流式接口同构）。
     * Swagger 对 SSE 展示有限，手动验证建议用 {@code curl -N}。</p>
     *
     * <p>【为什么这个接口不走 {@link Result} 统一响应包装？】
     * SSE 的响应体是一连串事件流（先 event:citations 帧，再 data: 回答分片），
     * 不是单个 JSON 对象，无法套进 Result 结构——流式接口天然例外。</p>
     *
     * <p>事件流约定：
     * 1. <strong>event:citations</strong>——引用来源数组的 JSON（前端用
     *    {@code addEventListener('citations')} 接收，先拿到引用元数据）；
     * 2. <strong>data:</strong>（默认事件）——回答分片，逐块到达（前端用
     *    {@code onmessage} 接收，实现打字机效果）；
     * 3. 空上下文时只发一条 data 提示文本后结束；出错时发 event:error。</p>
     *
     * @param authentication 当前登录用户认证信息（提取 userId 传给 Service 做用户维限流）
     * @param request        问题 + 检索参数（{@code @Valid} 校验，与同步接口一致）
     * @return {@link SseEmitter}，由 Spring MVC 接管其生命周期并流式写出响应
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(Authentication authentication,
                                 @Valid @RequestBody RagChatRequest request) {
        // 在 Controller 层创建 SseEmitter 并返回，Service 只负责「往里面推事件」。
        // 超时显式设为 120 秒，避免长连接被默认短超时提前掐断。
        // 注意：限流在 Service 方法开头执行（尚未推流、SSE 头未发出），
        // 超限时抛出的 429 仍可被全局异常处理转成正常 HTTP 状态码。
        Long userId = ((EnterpriseUser) authentication.getPrincipal()).userId();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        ragChatService.chatStream(userId, request.getQuestion(),
                request.resolveTopK(), request.resolveScoreThreshold(), emitter);
        return emitter;
    }
}
