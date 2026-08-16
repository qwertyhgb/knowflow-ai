-- V1：创建用户表 sys_user。
--
-- 兼容性说明：
-- 本脚本需要同时跑在 MySQL（生产/本地）与 H2（测试库，MODE=MySQL）上。
-- MySQL 特有、H2 不支持的语法（ENGINE / CHARSET / COLLATE / COMMENT / ON UPDATE）
-- 一律用 MySQL 可执行注释 /*! ... */ 包裹：MySQL 会执行其中的内容，
-- H2 则把整段当作普通注释忽略，从而一份脚本两个数据库都能通过。
--
-- 注意：已发布的迁移脚本禁止修改（Flyway 会用 checksum 校验）；
-- 后续结构变更请新增 V2__xxx.sql，而不是改动本文件。

CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT
        /*! COMMENT '主键 ID' */,
    email         VARCHAR(254) NOT NULL
        /*! COMMENT '邮箱，登录账号' */,
    password_hash VARCHAR(255) NOT NULL
        /*! COMMENT '密码哈希值，不存明文' */,
    nickname      VARCHAR(50)  NOT NULL
        /*! COMMENT '昵称' */,
    status        TINYINT      NOT NULL DEFAULT 1
        /*! COMMENT '账号状态：0-禁用，1-正常' */,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    -- 业务唯一性用唯一约束保证：邮箱不允许重复注册。
    CONSTRAINT uk_sys_user_email UNIQUE (email)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '用户表' */;

-- 状态查询是明确且可能高频的过滤条件，为其建立普通索引。
CREATE INDEX idx_sys_user_status ON sys_user (status);
