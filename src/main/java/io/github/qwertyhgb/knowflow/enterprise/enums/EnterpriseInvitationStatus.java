package io.github.qwertyhgb.knowflow.enterprise.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 企业成员邀请状态。
 *
 * <p>与 {@link EnterpriseMemberRole} 一致，状态使用<strong>字符串</strong>而非数字存储
 * （数据库列 {@code status} 为 VARCHAR(16)），与 V4 迁移脚本注释约定的
 * 「可读字符串」保持一致：状态直接以可读的英文单词落库，语义自解释，
 * 后续调整状态集合时无需迁移改表。</p>
 *
 * <p>{@link EnumValue} 标注在 {@link #value} 上，MyBatis-Plus 读写枚举时使用
 * 该字符串值（而非默认的 {@link #name()}），与表列定义一致；
 * Jackson 序列化仍默认输出枚举名称，恰好与 {@code value} 同形。</p>
 *
 * <p><strong>状态机：</strong>{@code PENDING → ACCEPTED / REVOKED / EXPIRED}。
 * 三个终态不可逆；重新邀请会生成一条新的 PENDING 记录（历史保留）。</p>
 */
@Getter
public enum EnterpriseInvitationStatus {

    /** 待接受：邀请已发出，等待被邀请人处理，对应数据库 {@code 'PENDING'}。 */
    PENDING("PENDING"),

    /** 已接受：被邀请人已凭此邀请加入企业，对应数据库 {@code 'ACCEPTED'}。 */
    ACCEPTED("ACCEPTED"),

    /** 已撤销：邀请人主动作废邀请（接受前），对应数据库 {@code 'REVOKED'}。 */
    REVOKED("REVOKED"),

    /** 已过期：超过 {@code expires_at} 仍未接受，对应数据库 {@code 'EXPIRED'}。 */
    EXPIRED("EXPIRED");

    /**
     * 数据库中的持久化取值，必须与 {@code enterprise_invitation.status}
     * 列定义的可取值保持一致。
     *
     * <p>{@link EnumValue} 的作用：指定 MyBatis-Plus 持久化该枚举时使用
     * <strong>本字段的值</strong>（如 {@code "PENDING"}），而不是默认的枚举名，
     * 保证落库值与 V4 脚本注释约定的取值集合完全一致。</p>
     */
    @EnumValue
    private final String value;

    /**
     * @param value 数据库持久化取值，必须与 {@code enterprise_invitation.status} 列约定一致
     */
    EnterpriseInvitationStatus(String value) {
        this.value = value;
    }
}
