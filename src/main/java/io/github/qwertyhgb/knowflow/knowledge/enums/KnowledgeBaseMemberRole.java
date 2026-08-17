package io.github.qwertyhgb.knowflow.knowledge.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 知识库成员角色。
 *
 * <p>资源级权限的载体：表达「用户在该知识库上能做哪些操作」，
 * 与「企业级权限码（{@code @PreAuthorize} + authorities）表达能否做某类操作」互为两层。
 * 字符串枚举直接落库，数据库列 {@code member_role} 为 VARCHAR(16)。</p>
 *
 * <p><strong>三种角色语义：</strong></p>
 * <ul>
 *   <li>{@code VIEWER}（只读）——仅可查看知识库内容，是最低权限（默认角色）；</li>
 *   <li>{@code EDITOR}（可编辑）——可新增/修改/删除知识库内的文档资源；</li>
 *   <li>{@code ADMIN}（管理）——除编辑能力外，还可管理知识库成员与其角色。</li>
 * </ul>
 */
@Getter
public enum KnowledgeBaseMemberRole {

    /** 只读成员，对应数据库 {@code 'VIEWER'}（默认角色）。 */
    VIEWER("VIEWER"),

    /** 可编辑成员，对应数据库 {@code 'EDITOR'}。 */
    EDITOR("EDITOR"),

    /** 知识库管理员：可编辑并管理成员，对应数据库 {@code 'ADMIN'}。 */
    ADMIN("ADMIN");

    /** 数据库中的持久化取值，与 {@code knowledge_base_member.member_role} 列定义保持一致。 */
    @EnumValue
    private final String value;

    KnowledgeBaseMemberRole(String value) {
        this.value = value;
    }
}
