package io.github.qwertyhgb.knowflow.ticket.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 工单状态。
 *
 * <p>与 {@code ticket.status} 列(VARCHAR(20))对应,使用<strong>字符串</strong>而非
 * 数字存储,语义自解释。</p>
 *
 * <p><strong>状态机(单向流转):</strong></p>
 * <pre>{@code
 * OPEN(待处理) → ASSIGNED(已分配) → PROCESSING(处理中) → RESOLVED(已解决) → CLOSED(已关闭)
 * }</pre>
 *
 * <p><strong>为什么用状态机约束流转?</strong>工单处理是有序的协作流程:
 * 必须先分配客服(ASSIGNED)、客服开始处理(PROCESSING)、处理完标记解决(RESOLVED)、
 * 客户确认后关闭(CLOSED)。跳步(如 OPEN 直接 RESOLVED)会让协作流程失去可审计性。
 * 状态流转的合法性与推进操作(分配/回复/关闭)在后续步骤实现,通过校验
 * 「当前状态 → 目标状态」是否为合法相邻流转来保证单向推进。</p>
 *
 * <p><strong>本步只用到 OPEN:</strong>创建工单时初始状态固定为 OPEN——
 * 表示「新工单等待处理」。AI 自动回答不改变状态:无论 AI 是否答出,
 * 工单仍需人工确认/分配,状态保持 OPEN。</p>
 *
 * <p>{@link EnumValue} 标注在 {@link #value} 上,MyBatis-Plus 读写枚举时使用该字符串值;
 * Jackson 序列化默认输出枚举名称,恰好与 {@code value} 同形。</p>
 */
@Getter
public enum TicketStatus {

    /** 待处理:新创建的工单,尚未分配客服,对应数据库 {@code 'OPEN'}。 */
    OPEN("OPEN", "待处理"),

    /** 已分配:工单已指定处理客服,对应数据库 {@code 'ASSIGNED'}(后续步骤使用)。 */
    ASSIGNED("ASSIGNED", "已分配"),

    /** 处理中:客服正在处理,对应数据库 {@code 'PROCESSING'}(后续步骤使用)。 */
    PROCESSING("PROCESSING", "处理中"),

    /** 已解决:客服标记处理完成,对应数据库 {@code 'RESOLVED'}(后续步骤使用)。 */
    RESOLVED("RESOLVED", "已解决"),

    /** 已关闭:客户确认或超时自动关闭,终态,对应数据库 {@code 'CLOSED'}(后续步骤使用)。 */
    CLOSED("CLOSED", "已关闭");

    /** 数据库中的持久化取值,必须与 {@code ticket.status} 列定义的可取值保持一致。 */
    @EnumValue
    private final String value;

    /** 中文描述:管理后台展示与状态标签渲染用(仅展示用途,不参与持久化)。 */
    private final String description;

    TicketStatus(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
