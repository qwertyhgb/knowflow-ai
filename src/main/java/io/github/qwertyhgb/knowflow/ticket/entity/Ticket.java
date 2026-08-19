package io.github.qwertyhgb.knowflow.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketCategory;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketPriority;
import io.github.qwertyhgb.knowflow.ticket.enums.TicketStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 工单实体,对应表 {@code ticket}(V14)。
 *
 * <p>实体只做「数据库 ↔ Java」的持久化映射,不直接返回给前端(边界规范),
 * 对外展示由 {@code TicketVO} 承担。</p>
 *
 * <p><strong>为什么工单挂企业作用域(enterprise_id):</strong>
 * 工单由企业成员提交、企业客服处理,AI 自动回答检索的也是该企业的知识库;
 * 多租户下没有企业归属的工单无法确定数据隔离边界。</p>
 *
 * <p>时间字段使用 {@link Instant}(UTC 语义),由 Service 层用注入的 {@code Clock}
 * 显式赋值(项目约定:不用 MyBatis-Plus 自动填充,时间来源统一可注入、可冻结测试)。</p>
 */
@Getter
@Setter
@TableName("ticket")
public class Ticket {

    /** 工单 ID,自增主键(MyBatis-Plus 插入后自动回填)。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID:企业作用域归属判据之一(与 user_id 共同构成访问控制)。 */
    private Long enterpriseId;

    /** 提交人用户 ID:「我的工单」列表与详情归属校验的判据。 */
    private Long userId;

    /** 工单分类(CONSULTATION/ISSUE/FEEDBACK/OTHER)。 */
    private TicketCategory category;

    /** 优先级(LOW/MEDIUM/HIGH/URGENT)。 */
    private TicketPriority priority;

    /** 状态(OPEN/ASSIGNED/PROCESSING/RESOLVED/CLOSED),创建时恒为 OPEN。 */
    private TicketStatus status;

    /** 工单标题(一句话概述问题)。 */
    private String title;

    /** 工单描述(客户提交的问题详情,AI 自动回答以其为检索问题)。 */
    private String description;

    /** 分配的客服用户 ID:创建时未分配恒为 NULL(分配步骤使用)。 */
    private Long assigneeId;

    /** 创建时间(UTC)。 */
    private Instant createdAt;

    /** 更新时间(UTC):状态流转/回复时更新。 */
    private Instant updatedAt;
}
