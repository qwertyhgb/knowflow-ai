package io.github.qwertyhgb.knowflow.ticket.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 工单回复请求对象:提交人补充说明(USER)或客服处理回复(SUPPORT)。
 *
 * <p>回复角色不由客户端指定——由服务端按「提交人 / 被分配客服或管理员」身份判定,
 * 防止客户端伪造角色(如普通成员冒充 SUPPORT 发言)。</p>
 */
@Getter
@Setter
public class TicketReplyCreateRequest {

    /** 回复内容。 */
    @NotBlank(message = "回复内容不能为空")
    @Size(max = 5000, message = "回复内容不能超过5000字符")
    private String content;
}
