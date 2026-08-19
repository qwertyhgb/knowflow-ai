package io.github.qwertyhgb.knowflow.ticket.dto.request;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * 工单分配请求对象:管理员把工单分配给某企业成员(客服)。
 *
 * <p>只携带 {@code assigneeId}:目标工单由路径参数定位,操作者身份由认证主体
 * 取(客户端声明的身份不可信)。目标用户「是否是该企业正常成员」属于业务规则
 * (需要查库),由 Service 层校验——DTO 校验只做纯参数合法性(ID 为正数)。</p>
 */
@Getter
@Setter
public class TicketAssignRequest {

    /** 被分配的客服用户 ID:必须是该企业的正常成员,Service 层校验。 */
    @Positive(message = "客服 ID 必须为正数")
    private Long assigneeId;
}
