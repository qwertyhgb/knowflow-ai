package io.github.qwertyhgb.knowflow.enterprise.vo;

import io.github.qwertyhgb.knowflow.enterprise.entity.Enterprise;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseStatus;
import lombok.Getter;

import java.time.Instant;

/**
 * 企业响应对象。
 *
 * <p>仅暴露可安全下发给客户端的字段；{@code slug} 属于内部业务标识，
 * 当前版本不对外展示。时间字段使用 {@link Instant}，由 API 序列化层
 * 统一输出为 ISO-8601 UTC 字符串。</p>
 */
@Getter
public class EnterpriseVO {

    private final Long id;

    private final String name;

    private final EnterpriseStatus status;

    private final Instant createdAt;

    private EnterpriseVO(Long id, String name, EnterpriseStatus status, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * 由实体构建响应对象，避免把 Entity 直接返回给前端，同时不引入独立 Converter 工具类。
     */
    public static EnterpriseVO from(Enterprise enterprise) {
        return new EnterpriseVO(
                enterprise.getId(),
                enterprise.getName(),
                enterprise.getStatus(),
                enterprise.getCreatedAt());
    }
}
