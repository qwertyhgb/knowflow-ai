package io.github.qwertyhgb.knowflow.user.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 用户账号状态。
 *
 * <p><strong>双重视角约定：</strong></p>
 * <ul>
 *   <li><strong>持久化视角</strong>：{@link #code} 与数据库 {@code sys_user.status}
 *       字段的 TINYINT 取值一一对应，由 {@link EnumValue} 标注为 MyBatis-Plus
 *       存储枚举时使用的值。</li>
 *   <li><strong>API 展示视角</strong>：接口响应中默认输出语义明确的枚举名称
 *       （例如 {@code "NORMAL"}），而不是难以阅读的数字 {@code 1}。</li>
 * </ul>
 *
 * <p>这两个关注点是相互独立的：{@link EnumValue} 只决定「数据库存什么」，
 * 不影响 Jackson 对 API 输出「枚举名 or 数字」的选择。</p>
 */
@Getter
public enum UserStatus {

    /** 账号正常，可正常登录与使用，对应数据库 {@code status = 1}。 */
    NORMAL(1),

    /** 账号被禁用，登录时凭证校验通过后仍会被拒绝，对应数据库 {@code status = 0}。 */
    DISABLED(0);

    /**
     * 数据库中的持久化取值。
     *
     * <p>标注 {@link EnumValue} 后，MyBatis-Plus 在读写枚举字段时
     * 使用该值（而非默认的枚举名称 {@code name()}）与数据库交互，
     * 保证存的是 {@code 1}/{@code 0} 而不是 {@code "NORMAL"}/{@code "DISABLED"}。</p>
     */
    @EnumValue
    private final int code;

    /**
     * @param code 数据库持久化取值，必须与 {@code sys_user.status} 列定义保持一致
     */
    UserStatus(int code) {
        this.code = code;
    }
}
