package io.github.qwertyhgb.knowflow.enterprise.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 企业成员角色。
 *
 * <p>与 {@code EnterpriseStatus}/{@code EnterpriseMemberStatus} 不同，
 * 角色使用<strong>字符串</strong>而非数字存储（数据库列 {@code member_role} 为
 * VARCHAR(32)），与 V3 迁移脚本注释约定的「可扩展字符串」保持一致：角色直接以
 * 可读的英文单词落库，后续新增角色（如 BILLING、AUDITOR）时无需迁移改表。</p>
 *
 * <p>{@link EnumValue} 标注在 {@link #value} 上，MyBatis-Plus 读写枚举时使用
 * 该字符串值（而非默认的 {@link #name()}），与表列定义一致；
 * Jackson 序列化仍默认输出枚举名称，恰好与 {@link #value} 同形。</p>
 */
@Getter
public enum EnterpriseMemberRole {

    /** 企业所有者：创建企业的用户，拥有全部权限，对应数据库 {@code 'OWNER'}。 */
    OWNER("OWNER"),

    /** 企业管理员：可管理成员与大部分企业设置，对应数据库 {@code 'ADMIN'}。 */
    ADMIN("ADMIN"),

    /** 普通成员：仅可访问被授予的资源，对应数据库 {@code 'MEMBER'}（默认角色）。 */
    MEMBER("MEMBER");

    /**
     * 数据库中的持久化取值，与 {@code enterprise_member.member_role} 列定义保持一致。
     *
     * <p>{@link EnumValue} 的作用：指定 MyBatis-Plus 持久化该枚举时使用
     * <strong>本字段的值</strong>（如 {@code "OWNER"}），而不是默认的枚举名
     * {@link #name()}。好处有二：</p>
     * <ul>
     *   <li>数据库落的是明确指定的字符串，列值可控、可读；</li>
     *   <li>将来枚举常量重命名（如 {@code OWNER → ROLE_OWNER}）不影响已落库数据，
     *       数据库格式由 {@code value} 决定而非枚举名。</li>
     * </ul>
     *
     * <p><strong>边界提醒：</strong>{@link EnumValue} 只管 MyBatis-Plus 的
     * 数据库读写，与 Jackson 序列化无关。本枚举 {@code value} 与 {@code name()}
     * 恰好同形，因此 API 输出枚举名（{@code "OWNER"}）与数据库值一致，
     * 无需再为序列化做额外配置。</p>
     */
    @EnumValue
    private final String value;

    /**
     * @param value 数据库持久化取值，必须与 {@code enterprise_member.member_role} 列定义保持一致
     */
    EnterpriseMemberRole(String value) {
        this.value = value;
    }
}
