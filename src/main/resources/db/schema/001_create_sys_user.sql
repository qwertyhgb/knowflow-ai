CREATE TABLE `sys_user` (
    `id` BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `email` VARCHAR(254) NOT NULL COMMENT '邮箱，登录账号',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '密码哈希值，不存明文',
    `nickname` VARCHAR(50) NOT NULL COMMENT '昵称',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '账号状态：0-禁用，1-正常',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_email` (`email`),
    KEY `idx_sys_user_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户表';
