package io.github.qwertyhgb.knowflow.knowledge.vo;

import io.github.qwertyhgb.knowflow.knowledge.entity.KnowledgeBaseMember;
import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseMemberRole;
import lombok.Getter;

import java.time.Instant;

/**
 * 知识库成员响应对象。
 *
 * <p>只暴露知识库成员接口需要的字段，避免把 {@link KnowledgeBaseMember} 持久化实体
 * 直接返回给客户端。时间字段使用 {@link Instant}，由 API 序列化层统一输出为 ISO-8601
 * UTC 字符串；{@code memberRole} 输出语义明确的枚举名称。</p>
 */
@Getter
public class KnowledgeBaseMemberVO {

    private final Long id;

    private final Long knowledgeBaseId;

    private final Long userId;

    private final KnowledgeBaseMemberRole memberRole;

    private final Instant createdAt;

    private KnowledgeBaseMemberVO(Long id, Long knowledgeBaseId, Long userId,
                                  KnowledgeBaseMemberRole memberRole, Instant createdAt) {
        this.id = id;
        this.knowledgeBaseId = knowledgeBaseId;
        this.userId = userId;
        this.memberRole = memberRole;
        this.createdAt = createdAt;
    }

    /** 由知识库成员实体构建安全的 API 响应对象。 */
    public static KnowledgeBaseMemberVO from(KnowledgeBaseMember member) {
        return new KnowledgeBaseMemberVO(
                member.getId(),
                member.getKnowledgeBaseId(),
                member.getUserId(),
                member.getMemberRole(),
                member.getCreatedAt());
    }
}
