package io.github.qwertyhgb.knowflow.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseMemberRole;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 知识库成员实体，对应表 {@code knowledge_base_member}。
 *
 * <p>资源级权限的核心：标定「哪个用户拥有哪个知识库的何种角色权限」。
 * 成员角色（{@link KnowledgeBaseMemberRole}）表达用户在该知识库上能做哪些操作，与
 * 企业级权限码分属两层权限模型。</p>
 *
 * <p><strong>跨租户防串设计（V8 迁移）：</strong>本表冗余了 {@code enterpriseId}，
 * 并用复合外键 {@code (enterprise_id, user_id)} 引用 {@code enterprise_member
 * (enterprise_id, user_id)}，从数据库层面保证知识库成员「先是在对应企业内的成员，
 * 且只能挂到该企业自己的知识库」。「同一知识库内一个用户一条成员记录」
 * 由唯一键 {@code uk_knowledge_base_member_kb_user} 保证。</p>
 */
@Getter
@Setter
@TableName("knowledge_base_member")
public class KnowledgeBaseMember {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 知识库 ID，通过数据库外键关联 {@code knowledge_base.id}。 */
    private Long knowledgeBaseId;

    /** 冗余企业 ID（租户 ID），用于复合外键防跨租户挂成员。 */
    private Long enterpriseId;

    /** 用户 ID，通过数据库复合外键关联 {@code enterprise_member(user_id)}。 */
    private Long userId;

    /** 成员角色：VIEWER 只读，EDITOR 可编辑，ADMIN 管理。 */
    private KnowledgeBaseMemberRole memberRole;

    /** 创建时间（UTC）。 */
    private Instant createdAt;

    /** 更新时间（UTC）。 */
    private Instant updatedAt;
}
