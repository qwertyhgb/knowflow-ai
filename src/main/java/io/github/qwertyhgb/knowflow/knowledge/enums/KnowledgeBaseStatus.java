package io.github.qwertyhgb.knowflow.knowledge.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 知识库状态。
 *
 * <p>沿用 {@code EnterpriseDepartmentStatus} 的「数字存储 + 枚举名展示」约定：
 * <strong>持久化视角</strong>用 {@link #code}（TINYINT，与数据库列 {@code status}
 * 一致，由 {@link EnumValue} 标注）；<strong>API 展示视角</strong>默认输出语义明确的
 * 枚举名称（{@code "NORMAL"}/{@code "DISABLED"}），而不是难读的数字。</p>
 */
@Getter
public enum KnowledgeBaseStatus {

    /** 知识库正常，可正常使用，对应数据库 {@code status = 1}。 */
    NORMAL(1),

    /** 知识库被禁用，对应数据库 {@code status = 0}。 */
    DISABLED(0);

    /** 数据库中的持久化取值，与 {@code knowledge_base.status} 列定义保持一致。 */
    @EnumValue
    private final int code;

    KnowledgeBaseStatus(int code) {
        this.code = code;
    }
}
