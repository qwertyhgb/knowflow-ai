package io.github.qwertyhgb.knowflow.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseAccessMode;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 知识库实体，对应表 {@code knowledge_base}。
 *
 * <p>知识库是企业内的<strong>资源级</strong>数据单元：权限从「企业级」细化到
 * 「资源级」，本表(加上成员表 {@code knowledge_base_member})是这一粒度落地的载体。
 * 数据归属单个企业（租户），通过数据库外键 {@code enterprise_id} 关联。知识库名称
 * 在企业内不强制唯一，同名知识库靠 ID 区分（明确的设计决策，业务层不查重）。</p>
 *
 * <p>字段依赖已开启的驼峰映射（{@code map-underscore-to-camel-case: true}）自动转换，
 * 例如 {@code enterprise_id} 映射为 {@code enterpriseId}、{@code owner_user_id}
 * 映射为 {@code ownerUserId}。</p>
 */
@Getter
@Setter
@TableName("knowledge_base")
public class KnowledgeBase {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID（租户 ID），通过数据库外键关联 {@code enterprise.id}。 */
    private Long enterpriseId;

    /** 知识库名称；企业内不强制唯一，用 ID 区分同名知识库。 */
    private String name;

    /** 知识库描述，可为空。 */
    private String description;

    /** 访问模式：PRIVATE 仅成员可见，PUBLIC 企业内所有正常成员可见。 */
    private KnowledgeBaseAccessMode accessMode;

    /** 创建者用户 ID（企业成员），通过数据库外键关联 {@code sys_user.id}。 */
    private Long ownerUserId;

    private KnowledgeBaseStatus status;

    /** 创建时间（UTC）。 */
    private Instant createdAt;

    /** 更新时间（UTC）。 */
    private Instant updatedAt;
}
