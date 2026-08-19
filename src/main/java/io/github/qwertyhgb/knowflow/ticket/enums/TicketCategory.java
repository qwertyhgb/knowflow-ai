package io.github.qwertyhgb.knowflow.ticket.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 工单分类。
 *
 * <p>与 {@code ticket.category} 列(VARCHAR(20))对应,使用<strong>字符串</strong>而非
 * 数字存储,与 {@code AiMessageRole} 先例一致:可读英文单词落库,语义自解释。</p>
 *
 * <p><strong>为什么用固定枚举而非自由文本?</strong>分类的核心价值是「可统计、可路由」:
 * 按分类统计工单量、按分类路由给不同客服组,都依赖取值集合稳定。自由文本会让
 * 「问题反馈」「问题」「bug 反馈」混在一起,统计失去意义。后续若分类需要运营化配置
 * (管理员可增删),再演进为字典表;当前学习阶段固定枚举最简单。</p>
 *
 * <p>{@link EnumValue} 标注在 {@link #value} 上,MyBatis-Plus 读写枚举时使用该字符串值
 * (而非默认的 {@link #name()}),与表列定义一致;Jackson 序列化仍默认输出枚举名称,
 * 恰好与 {@code value} 同形。</p>
 */
@Getter
public enum TicketCategory {

    /** 咨询:客户对产品/服务的使用咨询,对应数据库 {@code 'CONSULTATION'}。 */
    CONSULTATION("CONSULTATION", "咨询"),

    /** 问题反馈:客户报告的功能异常或缺陷,对应数据库 {@code 'ISSUE'}。 */
    ISSUE("ISSUE", "问题反馈"),

    /** 建议:客户提出的改进建议,对应数据库 {@code 'FEEDBACK'}。 */
    FEEDBACK("FEEDBACK", "建议"),

    /** 其他:无法归入以上分类的工单,对应数据库 {@code 'OTHER'}。 */
    OTHER("OTHER", "其他");

    /** 数据库中的持久化取值,必须与 {@code ticket.category} 列定义的可取值保持一致。 */
    @EnumValue
    private final String value;

    /** 中文描述:管理后台展示与报表场景使用(仅展示用途,不参与持久化)。 */
    private final String description;

    TicketCategory(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
