-- V3：创建企业成员关系表 enterprise_member。
--
-- 一名用户可以加入多个企业；同一用户在同一企业中只能有一条成员记录。
-- 成员角色使用可扩展字符串，首期约定 OWNER、ADMIN、MEMBER。
-- 已发布的迁移脚本禁止修改；后续结构变更请新增更高版本迁移。

CREATE TABLE enterprise_member (
    id            BIGINT      NOT NULL AUTO_INCREMENT
        /*! COMMENT '企业成员关系 ID' */,
    enterprise_id BIGINT      NOT NULL
        /*! COMMENT '企业 ID（租户 ID）' */,
    user_id       BIGINT      NOT NULL
        /*! COMMENT '用户 ID' */,
    member_role   VARCHAR(32) NOT NULL DEFAULT 'MEMBER'
        /*! COMMENT '成员角色：OWNER、ADMIN、MEMBER' */,
    status        TINYINT     NOT NULL DEFAULT 1
        /*! COMMENT '成员状态：0-禁用，1-正常' */,
    joined_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '加入企业时间（UTC）' */,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! COMMENT '创建时间（UTC）' */,
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        /*! ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）' */,

    PRIMARY KEY (id),
    CONSTRAINT uk_enterprise_member_enterprise_user
        UNIQUE (enterprise_id, user_id),
    CONSTRAINT fk_enterprise_member_enterprise
        FOREIGN KEY (enterprise_id) REFERENCES enterprise (id),
    CONSTRAINT fk_enterprise_member_user
        FOREIGN KEY (user_id) REFERENCES sys_user (id)
) /*! ENGINE = InnoDB
     DEFAULT CHARSET = utf8mb4
     COLLATE = utf8mb4_0900_ai_ci
     COMMENT = '企业成员关系表' */;

-- 查询某个企业的有效成员。
CREATE INDEX idx_enterprise_member_enterprise_status
    ON enterprise_member (enterprise_id, status);

-- 查询某个用户加入的全部企业。
CREATE INDEX idx_enterprise_member_user_status
    ON enterprise_member (user_id, status);
