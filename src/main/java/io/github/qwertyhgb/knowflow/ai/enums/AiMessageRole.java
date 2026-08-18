package io.github.qwertyhgb.knowflow.ai.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * AI 会话消息角色。
 *
 * <p>与 {@code ai_message.role} 列（VARCHAR(20)）对应，使用<strong>字符串</strong>而非
 * 数字存储，与 EnterpriseInvitationStatus 先例一致：可读英文单词落库，语义自解释。</p>
 *
 * <p>{@link EnumValue} 标注在 {@link #value} 上，MyBatis-Plus 读写枚举时使用该字符串值
 * （而非默认的 {@link #name()}），与表列定义一致；Jackson 序列化仍默认输出枚举名称，
 * 恰好与 {@code value} 同形。</p>
 */
@Getter
public enum AiMessageRole {

    /** 用户消息：用户在该会话中发出的提问/输入，对应数据库 {@code 'USER'}。 */
    USER("USER"),

    /** 助手消息：AI 对该会话中用户消息的回答，对应数据库 {@code 'ASSISTANT'}。 */
    ASSISTANT("ASSISTANT");

    /**
     * 数据库中的持久化取值，必须与 {@code ai_message.role} 列定义的可取值保持一致。
     */
    @EnumValue
    private final String value;

    /**
     * @param value 数据库持久化取值，必须与 {@code ai_message.role} 列约定一致
     */
    AiMessageRole(String value) {
        this.value = value;
    }
}
