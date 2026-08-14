package io.github.qwertyhgb.knowflow.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.qwertyhgb.knowflow.user.enums.UserStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 用户实体，对应表 {@code sys_user}。
 *
 * <p>{@code password_hash} / {@code created_at} / {@code updated_at} 等字段
 * 依赖已开启的驼峰映射（{@code map-underscore-to-camel-case: true}）自动转换，
 * 无需逐字段声明 {@code @TableField}。</p>
 */
@Getter
@Setter
@TableName("sys_user")
public class User {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String email;

    private String passwordHash;

    private String nickname;

    private UserStatus status;

    private Instant createdAt;

    private Instant updatedAt;
}
