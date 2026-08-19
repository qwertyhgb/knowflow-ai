package io.github.qwertyhgb.knowflow.ticket.dto.request;

import io.github.qwertyhgb.knowflow.ticket.enums.TicketCategory;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建工单请求对象。
 *
 * <p>字段校验说明:</p>
 * <ul>
 *   <li>{@code title}/{@code description}:{@code @NotBlank} 拦截空串/纯空白,
 *       {@code @Size} 上限与表列定义呼应(title VARCHAR(200),description 应用层
 *       限 5000 字符——列用 LONGTEXT 兜底,见 V14 注释);</li>
 *   <li>{@code category}/{@code priority}:{@code @NotNull} 必填;类型直接用枚举,
 *       非法取值(不在枚举定义内)由 Jackson 反序列化失败兜底为 400,
 *       保证落库的分类/优先级永远是合法枚举值——这是「固定枚举可统计」的第一道闸。</li>
 * </ul>
 *
 * <p>enterpriseId 与 userId 不出现在请求体中:前者来自路径参数 + 企业上下文头,
 * 后者来自认证主体——客户端声明的身份不可信,服务端从可信来源取。</p>
 */
@Getter
@Setter
public class TicketCreateRequest {

    /** 工单标题:一句话概述问题,列表页展示。 */
    @NotBlank(message = "工单标题不能为空")
    @Size(max = 200, message = "工单标题不能超过200字符")
    private String title;

    /** 工单描述:客户提交的问题详情,AI 自动回答以其为检索问题。 */
    @NotBlank(message = "工单描述不能为空")
    @Size(max = 5000, message = "工单描述不能超过5000字符")
    private String description;

    /** 工单分类(必填,非法取值由反序列化失败兜底 400)。 */
    @NotNull(message = "工单分类不能为空")
    private TicketCategory category;

    /** 优先级(必填,非法取值由反序列化失败兜底 400)。 */
    @NotNull(message = "工单优先级不能为空")
    private TicketPriority priority;
}
