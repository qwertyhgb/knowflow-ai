package io.github.qwertyhgb.knowflow.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * AI 对话请求对象。
 *
 * <p>接收客户端发来的单轮用户消息。遵循项目 DTO 规范：Request DTO 只负责
 * 接收并校验特定接口的请求参数，不传给 Mapper，也不直接返回给前端。
 * Controller 用 {@code @Valid} 触发字段级校验（{@code @NotBlank}/{@code @Size}）。</p>
 */
@Getter
@Setter
public class AiChatRequest {

    /**
     * 用户消息，必填（不能为空白）。
     *
     * 【为什么限制长度？】
     * 1. 保护上下文窗口：超大输入会撑爆模型上下文，导致回答退化或直接报错；
     * 2. 控制成本：大模型按 token 计费，输入越长成本越高；
     * 3. 防止拖垮请求：超大输入会增加请求体注入与网络传输开销。
     * 后端的长度校验是安全边界，前端同样限制输入长度（体验层）。
     */
    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "消息长度不能超过2000")
    private String message;
}
