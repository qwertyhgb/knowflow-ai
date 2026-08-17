package io.github.qwertyhgb.knowflow.knowledge.vo;

import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBase;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseAccessMode;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseMemberRole;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseStatus;
import lombok.Getter;

import java.time.Instant;

/**
 * 知识库响应对象。
 *
 * <p>只暴露知识库接口需要的字段，避免把 {@link KnowledgeBase} 持久化实体直接
 * 返回给客户端。时间字段使用 {@link Instant}，由 API 序列化层统一输出为 ISO-8601
 * UTC 字符串；{@code accessMode}/{@code status} 输出语义明确的枚举名称。</p>
 *
 * <p>{@link #myRole} 表示<strong>当前请求用户在该知识库的成员角色</strong>：
 * 仅列表/详情接口按可见性填充——知识库成员得到对应角色（VIEWER/EDITOR/ADMIN），
 * PUBLIC 知识库的非成员得到 {@code null}（表示「仅仅是企业内成员、非该知识库成员」），
 * 创建接口返回 {@code null}。</p>
 */
@Getter
public class KnowledgeBaseVO {

    private final Long id;

    private final Long enterpriseId;

    private final String name;

    private final String description;

    private final KnowledgeBaseAccessMode accessMode;

    private final Long ownerUserId;

    private final KnowledgeBaseStatus status;

    private final Instant createdAt;

    /** 当前请求用户在该知识库的成员角色；可为 null（非该知识库成员，如 PUBLIC 知识库的非成员）。 */
    private final KnowledgeBaseMemberRole myRole;

    private KnowledgeBaseVO(Long id, Long enterpriseId, String name, String description,
                            KnowledgeBaseAccessMode accessMode, Long ownerUserId,
                            KnowledgeBaseStatus status, Instant createdAt,
                            KnowledgeBaseMemberRole myRole) {
        this.id = id;
        this.enterpriseId = enterpriseId;
        this.name = name;
        this.description = description;
        this.accessMode = accessMode;
        this.ownerUserId = ownerUserId;
        this.status = status;
        this.createdAt = createdAt;
        this.myRole = myRole;
    }

    /** 由知识库实体构建 API 响应对象，{@code myRole} 置为 {@code null}（创建接口场景）。 */
    public static KnowledgeBaseVO from(KnowledgeBase knowledgeBase) {
        return from(knowledgeBase, null);
    }

    /**
     * 由知识库实体 + 当前用户成员角色构建 API 响应对象（列表/详情接口场景）。
     *
     * <p>{@code myRole} 可为 {@code null}，表示当前用户不是该知识库成员；
     * 由调用方按可见性规则决定是否合法。</p>
     */
    public static KnowledgeBaseVO from(KnowledgeBase knowledgeBase, KnowledgeBaseMemberRole myRole) {
        return new KnowledgeBaseVO(
                knowledgeBase.getId(),
                knowledgeBase.getEnterpriseId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getAccessMode(),
                knowledgeBase.getOwnerUserId(),
                knowledgeBase.getStatus(),
                knowledgeBase.getCreatedAt(),
                myRole);
    }
}
