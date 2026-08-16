package io.github.qwertyhgb.knowflow.enterprise.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.enterprise.enums.EnterpriseStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 企业（租户）实体，对应表 {@code enterprise}。
 *
 * <p>{@code slug} 是面向 URL、接口参数和日志的稳定业务标识，企业名称允许后续修改；
 * 其余字段依赖已开启的驼峰映射（{@code map-underscore-to-camel-case: true}）自动转换。</p>
 */
@Getter
@Setter
@TableName("enterprise")
public class Enterprise {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 企业名称，允许后续修改。 */
    private String name;

    /** 企业唯一标识（slug），用于 URL 和接口参数，创建后保持不变。 */
    private String slug;

    private EnterpriseStatus status;

    private Instant createdAt;

    private Instant updatedAt;
}
