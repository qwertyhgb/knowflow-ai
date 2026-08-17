package io.github.qwertyhgb.knowflow.knowledge.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 知识库访问模式。
 *
 * <p>沿用 {@code EnterpriseMemberRole} 的「字符串枚举直接落库」约定：{@link EnumValue}
 * 标注在 {@link #value} 上，MyBatis-Plus 持久化时写入该字符串（而非枚举名），
 * 数据库列 {@code access_mode} 为 VARCHAR(16)，存可读英文单词。</p>
 *
 * <p><strong>两种模式语义：</strong></p>
 * <ul>
 *   <li>{@code PRIVATE}（私有，默认）——仅知识库成员（knowledge_base_member）可见可访问；</li>
 *   <li>{@code PUBLIC}（公开）——企业内所有正常成员可见，但仍需成员身份校验（跨租户不可见）。</li>
 * </ul>
 */
@Getter
public enum KnowledgeBaseAccessMode {

    /** 私有：仅知识库成员可见，对应数据库 {@code 'PRIVATE'}（默认模式）。 */
    PRIVATE("PRIVATE"),

    /** 公开：企业内所有正常成员可见，对应数据库 {@code 'PUBLIC'}。 */
    PUBLIC("PUBLIC");

    /** 数据库中的持久化取值，与 {@code knowledge_base.access_mode} 列定义保持一致。 */
    @EnumValue
    private final String value;

    KnowledgeBaseAccessMode(String value) {
        this.value = value;
    }
}
