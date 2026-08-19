package io.github.qwertyhgb.knowflow.ticket.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 工单优先级。
 *
 * <p>与 {@code ticket.priority} 列(VARCHAR(20))对应,使用<strong>字符串</strong>而非
 * 数字存储,语义自解释(日志与排查时一眼可读)。</p>
 *
 * <p><strong>优先级的用途:</strong>后续客服分配与处理排序的依据——URGENT 应最优先处理。
 * 本步(创建 + AI 自动回答)只做落库与展示,优先级驱动的 SLA/排序是后续步骤的主题。</p>
 *
 * <p>{@link EnumValue} 标注在 {@link #value} 上,MyBatis-Plus 读写枚举时使用该字符串值;
 * Jackson 序列化默认输出枚举名称,恰好与 {@code value} 同形。</p>
 */
@Getter
public enum TicketPriority {

    /** 低优先级:不阻塞使用的小问题,对应数据库 {@code 'LOW'}。 */
    LOW("LOW", "低"),

    /** 中优先级:默认优先级,对应数据库 {@code 'MEDIUM'}。 */
    MEDIUM("MEDIUM", "中"),

    /** 高优先级:影响主要功能,需尽快处理,对应数据库 {@code 'HIGH'}。 */
    HIGH("HIGH", "高"),

    /** 紧急:系统不可用/数据风险等级别的最高优先级,对应数据库 {@code 'URGENT'}。 */
    URGENT("URGENT", "紧急");

    /** 数据库中的持久化取值,必须与 {@code ticket.priority} 列定义的可取值保持一致。 */
    @EnumValue
    private final String value;

    /** 中文描述:管理后台展示用(仅展示用途,不参与持久化)。 */
    private final String description;

    TicketPriority(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
