package io.github.qwertyhgb.knowflow.user.vo;

import io.github.qwertyhgb.knowflow.user.entity.User;
import io.github.qwertyhgb.knowflow.user.enums.UserStatus;
import lombok.Getter;

import java.time.Instant;

/**
 * 用户响应对象。
 *
 * <p>仅暴露可安全下发给客户端的字段，<strong>绝不包含 {@code passwordHash}</strong>。
 * 时间字段使用 {@link Instant}，由 API 序列化层统一输出为 ISO-8601 UTC 字符串。</p>
 */
@Getter
public class UserVO {

    private final Long id;

    private final String email;

    private final String nickname;

    private final UserStatus status;

    private final Instant createdAt;

    private final Instant updatedAt;

    private UserVO(Long id, String email, String nickname, UserStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.email = email;
        this.nickname = nickname;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 由实体构建响应对象，避免把 Entity 直接返回给前端，同时不引入独立 Converter 工具类。
     */
    public static UserVO from(User user) {
        return new UserVO(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
