package io.github.qwertyhgb.knowflow.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话内对话请求对象。
 *
 * <p>入参只有用户消息：会话 ID 走路径参数（资源定位），消息内容走 body（数据）。
 * 校验与 AiChatRequest 一致：{@code @NotBlank} 拦截空串/纯空白，
 * {@code @Size(max = 2000)} 限制长度（对话消息不会超过 2000 字符，超长基本是误用）。</p>
 */
@Getter
@Setter
public class ConversationChatRequest {

    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "消息长度不能超过2000")
    private String message;
}
