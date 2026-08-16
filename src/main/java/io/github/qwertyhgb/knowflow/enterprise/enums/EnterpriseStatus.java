package io.github.qwertyhgb.knowflow.enterprise.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 企业状态。
 *
 * <p><strong>双重视角约定：</strong></p>
 * <ul>
 *   <li><strong>持久化视角</strong>：{@link #code} 与数据库 {@code enterprise.status}
 *       字段的 TINYINT 取值一一对应，由 {@link EnumValue} 标注为 MyBatis-Plus
 *       存储枚举时使用的值。</li>
 *   <li><strong>API 展示视角</strong>：接口响应中默认输出语义明确的枚举名称
 *       （例如 {@code "NORMAL"}），而不是难以阅读的数字 {@code 1}。</li>
 * </ul>
 */
@Getter
public enum EnterpriseStatus {

    /** 企业正常，可正常使用，对应数据库 {@code status = 1}。 */
    NORMAL(1),

    /** 企业被禁用，对应数据库 {@code status = 0}。 */
    DISABLED(0);

    /** 数据库中的持久化取值，与 {@code enterprise.status} 列定义保持一致。 */
    @EnumValue
    private final int code;

    EnterpriseStatus(int code) {
        this.code = code;
    }
}
