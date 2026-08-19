package io.github.qwertyhgb.knowflow.ticket.vo;

import io.github.qwertyhgb.knowflow.ticket.entity.Ticket;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketCategory;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketPriority;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketStatus;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * 工单响应对象(单条工单信息,不含回复)。
 *
 * <p>工单列表/创建接口的返回体中的单条结构,完整回复历史见 {@link TicketDetailVO}。
 * 时间字段使用 {@link Instant},由 API 序列化层统一输出为 ISO-8601 UTC 字符串。</p>
 */
@Getter
public class TicketVO {

    /** 工单 ID。 */
    private final Long id;

    /** 所属企业 ID。 */
    private final Long enterpriseId;

    /** 提交人用户 ID。 */
    private final Long userId;

    /** 工单分类。 */
    private final TicketCategory category;

    /** 优先级。 */
    private final TicketPriority priority;

    /** 状态(本步创建后恒为 OPEN)。 */
    private final TicketStatus status;

    /** 工单标题。 */
    private final String title;

    /** 工单描述。 */
    private final String description;

    /** 分配的客服用户 ID(本步恒为 null,分配步骤使用)。 */
    private final Long assigneeId;

    /** 创建时间(UTC),列表按此倒序。 */
    private final Instant createdAt;

    /** 更新时间(UTC)。 */
    private final Instant updatedAt;

    private TicketVO(Long id, Long enterpriseId, Long userId, TicketCategory category,
                     TicketPriority priority, TicketStatus status, String title,
                     String description, Long assigneeId, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.enterpriseId = Objects.requireNonNull(enterpriseId, "enterpriseId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.priority = Objects.requireNonNull(priority, "priority must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.title = title;
        this.description = description;
        this.assigneeId = assigneeId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 由工单实体构建安全的 API 响应对象。 */
    public static TicketVO from(Ticket ticket) {
        return new TicketVO(
                ticket.getId(),
                ticket.getEnterpriseId(),
                ticket.getUserId(),
                ticket.getCategory(),
                ticket.getPriority(),
                ticket.getStatus(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getAssigneeId(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt());
    }
}
