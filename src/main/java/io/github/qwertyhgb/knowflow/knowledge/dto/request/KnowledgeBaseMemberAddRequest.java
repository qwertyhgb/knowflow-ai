package io.github.qwertyhgb.knowflow.knowledge.dto.request;

import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseMemberRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * 添加知识库成员请求参数。
 *
 * <p>指定目标用户（须为该企业正常成员，由服务层校验）与在知识库内的角色。
 * 目标用户是否已存在成员记录由服务层查重（409），数据库唯一键作兜底。</p>
 */
@Getter
@Setter
public class KnowledgeBaseMemberAddRequest {

    /** 目标用户 ID，须为正数。 */
    @Positive(message = "用户ID必须为正数")
    private Long userId;

    /** 知识库成员角色：VIEWER 只读 / EDITOR 可编辑 / ADMIN 管理。 */
    @NotNull(message = "成员角色不能为空")
    private KnowledgeBaseMemberRole memberRole;
}
