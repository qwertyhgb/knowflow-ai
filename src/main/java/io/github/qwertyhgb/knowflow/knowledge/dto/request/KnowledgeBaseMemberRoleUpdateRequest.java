package io.github.qwertyhgb.knowflow.knowledge.dto.request;

import io.github.qwertyhgb.knowflow.knowledge.enums.KnowledgeBaseMemberRole;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改知识库成员角色请求参数。
 */
@Getter
@Setter
public class KnowledgeBaseMemberRoleUpdateRequest {

    /** 目标角色：VIEWER 只读 / EDITOR 可编辑 / ADMIN 管理。 */
    @NotNull(message = "成员角色不能为空")
    private KnowledgeBaseMemberRole memberRole;
}
