package io.github.qwertyhgb.knowflow.ticket.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 工单回复角色:标记每条回复由谁发出。
 *
 * <p>与 {@code ticket_reply.role} 列(VARCHAR(20))对应,使用<strong>字符串</strong>而非
 * 数字存储,语义自解释。</p>
 *
 * <p><strong>为什么回复要区分角色?</strong>工单的回复历史是一个多方对话流:
 * 客户补充说明(USER)、AI 基于知识库的自动回答(AI)、人工客服的处理回复(SUPPORT)。
 * 前端按角色渲染不同的气泡样式(如 AI 回复展示「AI 助手」标签 + 引用卡片,
 * 客服回复展示客服姓名),客服也能一眼看出哪些内容是 AI 说的、哪些是同事处理的
 * ——这对「AI 说过什么必须可追溯」的信任建立尤为重要。</p>
 *
 * <p>{@link EnumValue} 标注在 {@link #value} 上,MyBatis-Plus 读写枚举时使用该字符串值;
 * Jackson 序列化默认输出枚举名称,恰好与 {@code value} 同形。</p>
 */
@Getter
public enum TicketReplyRole {

    /** 用户(工单提交人)的回复/补充说明,对应数据库 {@code 'USER'}。 */
    USER("USER", "用户"),

    /** AI 助手基于知识库的自动回答(含转人工提示),对应数据库 {@code 'AI'}。 */
    AI("AI", "AI 助手"),

    /** 人工客服的处理回复,对应数据库 {@code 'SUPPORT'}(后续步骤使用)。 */
    SUPPORT("SUPPORT", "客服");

    /** 数据库中的持久化取值,必须与 {@code ticket_reply.role} 列定义的可取值保持一致。 */
    @EnumValue
    private final String value;

    /** 中文描述:前端气泡标签渲染用(仅展示用途,不参与持久化)。 */
    private final String description;

    TicketReplyRole(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
